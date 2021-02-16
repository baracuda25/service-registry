# service-registry

A simple version of service registry which performs the validation of the deployment descriptor and creates microservices in case of all rules are satisfied.

It's implemednted as RESTFul service which has two operations :

  - deploy new microservices
  - get currentry running microservices

There is no real microservice deployment happening in the background, just intialization of the microservices objects which act as "successful" deployment.

There are following validations implemented :

 - unique single point microservice
 - health check for dependencies of the single point microservice
 - cyclic dependencies 

The service doesn't persist any information in the storage which means that after the restart the state is gone, as a possible improvement would be to use Neo4J
for microservices topology and some relational DB for microservice description storage.    

Instead of Neo4J there was http://www.scala-graph.org/ used for the operations with the microservices topology.

For the RESTFul implementation the choice was to use AkkaHttp with some Akka actors as a proxy between controller and business layers.

The project can be packaged as docker container with the help of sbt-native-packager. Just execute docker:publishLocal as an example.

Afterward it should be launched with the following command docker run -p 9000:9000 service-registry:0.1-SNAPSHOT since the application expects to be listening
port 9000(normally should be exposed as a configuration). 

In order to test that application is working following curl operations can be performed :

curl -H "Content-type: application/json" -X POST -d '[{ "name": "A", "entryPoint" : true, "replicas": 1, "dependencies": [ "B", "C" ], "healthy":true}, { "name": "B", "replicas": 1, "dependencies": [ "D" ], "healthy" : true}, { "name": "C", "replicas": 2, "dependencies": [ "D" ], "healthy":true},{ "name": "D", "replicas": 2, "healthy" : true}]' http://127.0.0.1:9000/microservices/deploy

curl -X GET http://127.0.0.1:9000/microservices

There are also unit tests available which cover all the necessary business logic.
