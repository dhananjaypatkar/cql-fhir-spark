package org.opencds.cqf.cql.spark.transform;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.spark.api.java.function.MapPartitionsFunction;
import org.cqframework.cql.cql2elm.CqlTranslatorOptions;
import org.cqframework.cql.cql2elm.ModelManager;
import org.cqframework.cql.cql2elm.model.Model;
import org.cqframework.cql.elm.execution.VersionedIdentifier;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.opencds.cqf.cql.engine.execution.EvaluationResult;
import org.opencds.cqf.cql.engine.execution.LibraryLoader;
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver;
import org.opencds.cqf.cql.engine.model.ModelResolver;
import org.opencds.cqf.cql.engine.runtime.DateTime;
import org.opencds.cqf.cql.engine.runtime.Interval;
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider;
import org.opencds.cqf.cql.evaluator.CqlEvaluator;
import org.opencds.cqf.cql.evaluator.builder.CqlEvaluatorBuilder;
import org.opencds.cqf.cql.evaluator.builder.RetrieveProviderConfig;
import org.opencds.cqf.cql.evaluator.cql2elm.content.InMemoryLibraryContentProvider;
import org.opencds.cqf.cql.evaluator.cql2elm.content.LibraryContentProvider;
import org.opencds.cqf.cql.evaluator.cql2elm.content.fhir.EmbeddedFhirLibraryContentProvider;
import org.opencds.cqf.cql.evaluator.cql2elm.model.CacheAwareModelManager;
import org.opencds.cqf.cql.evaluator.engine.execution.TranslatingLibraryLoader;
import org.opencds.cqf.cql.evaluator.engine.model.CachingModelResolverDecorator;
import org.opencds.cqf.cql.evaluator.engine.retrieve.BundleRetrieveProvider;
import org.opencds.cqf.cql.evaluator.engine.terminology.BundleTerminologyProvider;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.BundleUtil;

public class EvaluatorMapPartitionsFunction implements MapPartitionsFunction<String, String> {
    private static final long serialVersionUID = 1L;

    private List<String> libraries;
    private String valueSetBundleJson;

    public EvaluatorMapPartitionsFunction(List libraries, String valueSetBundleJson) {
        this.libraries = libraries;
        this.valueSetBundleJson = valueSetBundleJson;
    }

    @SuppressWarnings("unchecked")
    private String toString(Object obj, IParser parser) {
        if (obj == null) {
            return "null";
        } else if (obj instanceof IBaseResource) {
            return parser.encodeResourceToString((IBaseResource) obj);
        } else if (obj instanceof Iterable) {
            StringBuilder builder = new StringBuilder();
            builder.append("[");
            List<String> values = new ArrayList<>();
            for (Object o : (Iterable<Object>) obj) {
                values.add(this.toString(o, parser));
            }
            builder.append(String.join(",\n", values));
            builder.append("]");
            return builder.toString();

        } else {
            return obj.toString();
        }
    }

    @Override
    public Iterator<String> call(Iterator<String> input) throws Exception {
        FhirContext fhirContext = FhirContext.forCached(FhirVersionEnum.R4);
        IParser parser = fhirContext.newJsonParser();


        // Set up LibraryLoader that translates from CQL
        Map<org.hl7.elm.r1.VersionedIdentifier, Model> modelCache = new HashMap<>();
        ModelManager modelManager = new CacheAwareModelManager(modelCache);
        List<LibraryContentProvider> contentProviders = new ArrayList<>();
        contentProviders.add(new InMemoryLibraryContentProvider(libraries));
        contentProviders.add(new EmbeddedFhirLibraryContentProvider());
        LibraryLoader libraryLoader = new TranslatingLibraryLoader(modelManager, contentProviders,
                CqlTranslatorOptions.defaultOptions());

        // Set up a terminology provider that reads from a bundle
        Bundle terminologyBundle = (Bundle) parser.parseResource(valueSetBundleJson);
        TerminologyProvider terminologyProvider = new BundleTerminologyProvider(fhirContext, terminologyBundle);

        // Set up model resolution (this is this bit that translates between CQL types
        // and the HAPI FHIR java structures)
        R4FhirModelResolver rawModelResolver = new R4FhirModelResolver();
        ModelResolver modelResolver = new CachingModelResolverDecorator(rawModelResolver);

        // Parameters for eval
        VersionedIdentifier libraryIdentifier = new VersionedIdentifier().withId("EXM124_FHIR4").withVersion("8.2.000");

        /* Interval measurementPeriod = new Interval(new DateTime(new BigDecimal("0"), 2019, 01, 01), true,
                new DateTime(new BigDecimal("0"), 2019, 12, 12), true);
 */
        List<String> results = new ArrayList<>();
        while (input.hasNext()) {
            String dataBundleJson = input.next();

            Bundle dataBundle = (Bundle)parser.parseResource(dataBundleJson);

            List<Patient> patients = BundleUtil.toListOfResourcesOfType(fhirContext, dataBundle, Patient.class);
            if (patients == null || patients.isEmpty() || patients.size() > 1) {
                //throw new Exception("Patient data bundles must have exactly 1 Patient resource");
                System.out.println("Patient data bundles must have exactly 1 Patient resource");
            }

            String patientId = patients.get(0).getIdElement().getIdPart();

            CqlEvaluatorBuilder builder = new CqlEvaluatorBuilder(
                new org.opencds.cqf.cql.evaluator.builder.data.RetrieveProviderConfigurer(
                    RetrieveProviderConfig.defaultConfig()));
    
            builder.withLibraryLoader(libraryLoader);
            builder.withTerminologyProvider(terminologyProvider);
    
            BundleRetrieveProvider bundleRetrieveProvider = new BundleRetrieveProvider(fhirContext, dataBundle);
       
            builder.withModelResolverAndRetrieveProvider("http://hl7.org/fhir", modelResolver, bundleRetrieveProvider);
    
            CqlEvaluator cqlEvaluator = builder.build();


            EvaluationResult evalResult = cqlEvaluator.evaluate(
                libraryIdentifier,
                Pair.of("Patient", patientId)/* ,
                Collections.singletonMap("Measurement Period", measurementPeriod) */);

            StringBuilder stringBuilder = new StringBuilder();
            for (Map.Entry<String, Object> entry : evalResult.expressionResults.entrySet()) {
                stringBuilder.append(entry.getKey() + ": " + this.toString(entry.getValue(), parser) + "\n");
            }

            results.add(stringBuilder.toString());
        }

        return results.iterator();
    }
}