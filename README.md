# cql-fhir-spark

## Overview

<li>Attempt to run  [cql-evaluator](https://github.com/DBCG/cql-evaluator) on Spark
<li>Validated and tested on Azure HDInsight Spark cluster with FHIR R4
<li>Now runs on kubernetes via spark kubernetes operator
<li>Currenly hardcoded for measure based on Based on CMS124v7 - Cervical Cancer Screening <<Check class EvaluatorMapPartitionsFunction >>
<li>Add cql files in "cql" folder
<li>Add patient fhir in "fhir" folder
<li>Build, tag and push image and deploy on kubernetes using deploy.yaml

### TODO: write detailed docs and remove hardcoding and make use of job parameters
