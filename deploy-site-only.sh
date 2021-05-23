#!/bin/bash
 mvn clean generate-resources aws:property@prop aws:deployS3@s3 
