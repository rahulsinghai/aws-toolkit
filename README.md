# AWS Toolkit

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/be796a67115644fca193534609151667)](https://www.codacy.com/manual/rahulsinghai/aws-toolkit?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=rahulsinghai/aws-toolkit&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/rahulsinghai/aws-toolkit/branch/master/graph/badge.svg)](https://codecov.io/gh/rahulsinghai/aws-toolkit)
[![Build Status](https://travis-ci.org/rahulsinghai/aws-toolkit.svg?branch=master)](https://travis-ci.org/rahulsinghai/aws-toolkit)
[![Gitter](https://badges.gitter.im/rahulsinghai-aws-toolkit/community.svg)](https://gitter.im/rahulsinghai-aws-toolkit/community?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

AWS Toolkit enables AWS admins to easily work with Amazon Web Services using ReST APIs.

AWS Toolkit actors basically utilize AWS SDK for Java APIs.

## Supported Services

### Helper

- [Home page](http://localhost:8080/)
- [Status](http://localhost:8080/status)
- [Version](http://localhost:8080/version)
- [Ping](http://localhost:8080/ping)

### AMI

- [List AMIs](http://localhost:8080/ami/list?owner=Self)
- ~~[Create new AMI (idempotent)](http://localhost:8080/ami/create?)~~

### Network

- [Create VPC](http://localhost:8080/vpc/createVpc?cidr=10.0.0.0%2F28&amp;nameTag=awsToolkitExVPC)
- [Create Subnet](http://localhost:8080/subnet/createSubnet?cidr=10.0.0.0%2F28&amp;vpcId=vpc-044ece86f611fba17&amp;nameTag=awsToolkitExSubnet)

### EC2

- [Create EC2 instance](http://localhost:8080/ec2/createInstance?imageId=ami-032598fcc7e9d1c7a&amp;instanceType=t2.micro&amp;minCount=1&amp;maxCount=1&amp;associatePublicIpAddress=false&amp;subnetId=subnet-07ed435c416b9e9bf&amp;groups=sg-0c11c7bc3be82e337&amp;nameTag=awsToolkitExInst)
- [Start EC2 instance](http://localhost:8080/ec2/startInstance?instanceId=i-1234567890abcdef0)
- [Stop EC2 instance](http://localhost:8080/ec2/stopInstance?instanceId=i-1234567890abcdef0)
- [Reboot EC2 instance](http://localhost:8080/ec2/rebootInstance?instanceId=i-1234567890abcdef0)


## How to use

- Compiling & building fat JAR: `sbt clean compile test assembly`
- Run the app using `java -jar aws-toolkit.jar`

## License

Copyright 2020 - Rahul Singhai

Apache License, Version 2.0