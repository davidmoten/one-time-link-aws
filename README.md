# one-time-link-aws

This is the source for https://onetimelink.davidmoten.org/site/index.html which is an AWS deployment of https://github.com/davidmoten/one-time-link. 

This project uses a neat trick to ensure exactly-once delivery of an encrypted message by creating a FIFO SQS queue per message (and destroying the message and queue on read).

<img src="https://github.com/davidmoten/one-time-link/raw/master/src/docs/one-time-link.gif"/>

## Sequence Diagram

<img src="https://github.com/davidmoten/one-time-link-aws/raw/master/src/main/webapp/sequence-diagram.svg"/>

## Deployment

### Prepare

* Create certificate in AWS Certificate Manager (your own region is fine, ignore old advice that you need to use us-east-1), *don't* expand certificate name during wizard to request Route 53 update CNAME records because the routing won't use CNAME.
* make sure AWS deploying user deploying CloudFormation has UpdateDistribution on '*' permission (or Full Access)
* make sure AWS deploying user deploying CloudFormation has Certificate Manager access (Full will work obviously but you can probably narrow that)

### Deploy
To deploy you need your AWS credential pair (encrypted preferrably but up to you) in `.m2/settings.xml` with the name `my.aws`. If you want to use a different serverId then run the command below with the extra argument `-Dserver.id=<YOUR_SERVER_ID>`.

```bash
./deploy.sh
```
The deploy script does these steps:

* create artifact bucket if does not exist
* deploy artifact to artifact bucket
* create public bucket if does not exist
* deploy static resources to public bucket
* deploy apig and lambda via cloudformation that references artifact object and public bucket in apig methods
* generate cert for new domain in AWS Certificate Manager
* set new domain as alias for apig hostname
* associate new cert with apig
* create Route53 recordset to associate domain name with api gateway

### Releases
A properly tagged version is deployed if you run

```java
./release.sh <VERSION>
```
