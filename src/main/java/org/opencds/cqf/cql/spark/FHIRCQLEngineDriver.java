package org.opencds.cqf.cql.spark;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.SparkSession;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.opencds.cqf.cql.spark.bundle.BundleCreator;
import org.opencds.cqf.cql.spark.transform.EvaluatorMapPartitionsFunction;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import scala.Tuple2;

public class FHIRCQLEngineDriver {

    public static void main(String[] args) throws IOException {
        System.out.println("Starting driver with parameters "+Arrays.toString(args));
        String resourcePath = args[0];
        String cqlPath = args[1];
        String valueSetPath = args[2];
        String outputPath = args[3];
        
        SparkSession spark = SparkSession.builder().appName("CQL on FHIR on Spark")
                .getOrCreate();
        System.out.println("Craeted spark context...");
        FhirContext fhirContext = FhirContext.forCached(FhirVersionEnum.R4);
        BundleCreator bundleCreator = new BundleCreator(fhirContext);
        IParser parser = fhirContext.newJsonParser();
        System.out.println("Created FHIR R4 context and bundle creator ");

        
        // Load all ValueSets into a Bundle        
        RDD<Tuple2<String,String>> valusetData = spark.sparkContext().wholeTextFiles(valueSetPath,1);
        
        JavaRDD<String> valuesets = valusetData.toJavaRDD().map(new Function<Tuple2<String,String>, String>() {
			private static final long serialVersionUID = -497521272426581432L;

			@Override
        	public String call(Tuple2<String, String> v1) throws Exception {
        		// TODO Auto-generated method stub
        		return v1._2();
        	}
		});
    
        IBaseBundle terminologyBundle = bundleCreator.bundleFiles(valuesets.collect());
        String valueSetBundleJson = parser.encodeResourceToString(terminologyBundle);
        System.out.println("valueSetBundleJson "+valueSetBundleJson);
        
        Broadcast<String> valuesetBroadCast = spark.sparkContext().broadcast(
        		valueSetBundleJson,
        		scala.reflect.ClassManifestFactory.fromClass(String.class)
        );
        
        
        // Load CQL and set up CQL Translation
        RDD<Tuple2<String,String>> cqlData = spark.sparkContext().wholeTextFiles(cqlPath,1);
        JavaRDD<String> cqls = cqlData.toJavaRDD().map(new Function<Tuple2<String,String>, String>() {
			private static final long serialVersionUID = -7412207277141949337L;

			@Override
        	public String call(Tuple2<String, String> v1) throws Exception {
        		return v1._2();
        	}
		});
       
        List<String> cqlLibraries = cqls.collect();//readDirectoryIntoStrings(cqlPath);
        
        Broadcast<List> cqlLibBroadcast = spark.sparkContext().broadcast(
        		cqlLibraries,
        		scala.reflect.ClassManifestFactory.fromClass(List.class)
        );
        
        RDD<Tuple2<String,String>> patients = spark.sparkContext().wholeTextFiles(resourcePath,10);
    	JavaRDD<String> patrdd = patients.toJavaRDD().map(new Function<Tuple2<String,String>, String>() {
 			private static final long serialVersionUID = -7412207277141949337L;
 			@Override
         	public String call(Tuple2<String, String> v1) throws Exception {
 				FhirContext fhirContext = FhirContext.forCached(FhirVersionEnum.R4);
 				BundleCreator bundleCreator = new BundleCreator(fhirContext);
 				IParser parser = fhirContext.newJsonParser();
         		return parser.encodeResourceToString(bundleCreator.bundleFiles(v1._2()));
         	}
 		});
    	Dataset<String> dataBundleJson = spark.createDataset(patrdd.rdd(), Encoders.STRING()); 

        // Evaluate for each patient
        Dataset<String> results = dataBundleJson
                .mapPartitions(new EvaluatorMapPartitionsFunction(cqlLibBroadcast.value(), valuesetBroadCast.value()), Encoders.STRING());

        // Write results
        results.write().text(outputPath);

        spark.stop();
    }
}