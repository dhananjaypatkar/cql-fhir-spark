FROM gcr.io/spark-operator/spark:v3.1.1
COPY target/cql-fhir-spark-1.0.jar /opt/spark/examples/
COPY cql/ /opt/cql/
COPY valuesets/ /opt/valuesets/
COPY fhir /opt/fhir/