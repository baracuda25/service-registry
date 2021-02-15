package com.tarai.service.registry

import cats.data.{ NonEmptyChain, NonEmptyList }
import cats.implicits._
import scalax.collection.Graph
import scalax.collection.GraphEdge.DiEdge
import scalax.collection.GraphPredef._

object DeploymentRequestExecutor {

  type ExecutionResult[A] = Either[NonEmptyChain[DeploymentError], A]

  private final case class DeploymentMetada(descriptors: Map[String, MicroserviceDescriptor], sortedServices: List[String])

  private def buildTopologyGraph(descriptors: Seq[MicroserviceDescriptor]): ExecutionResult[Graph[String, DiEdge]] = {
    val edges = descriptors.flatMap(descriptor => descriptor.dependencies.toList.flatten.map(dep => dep ~> descriptor.name))
    val nodes = descriptors.map(_.name)
    val graph = Graph.from(nodes, edges)
    graph.findCycle.map(cycle => CyclicDescriptorError(cycle.edges.mkString(",")).leftNec).getOrElse(graph.rightNec)
  }

  private def sortTopology(graph: Graph[String, DiEdge]): ExecutionResult[List[String]] =
    graph.topologicalSort
      .fold(cycleNode => CyclicDescriptorError(cycleNode.value).leftNec, node => node.toLayered.toList.flatMap(_._2.map(_.value)).rightNec)

  private def findSingleEntryPoint(descriptors: Seq[MicroserviceDescriptor]): ExecutionResult[MicroserviceDescriptor] =
    NonEmptyList
      .fromList(descriptors.filter(_.entryPoint.exists(identity)).toList)
      .map(
        services =>
          if (services.size > 1) {
            NotSingleEntryPointError(services.toList.map(_.name)).leftNec
          }
          else {
            services.head.rightNec
          }
      )
      .getOrElse(NotSingleEntryPointError(Nil).leftNec)

  private def buildDeploymentMetadata(descriptors:    Seq[MicroserviceDescriptor],
                                      serviceGraph:   Graph[String, DiEdge],
                                      entryPoint:     MicroserviceDescriptor,
                                      sortedServices: List[String]): ExecutionResult[DeploymentMetada] = {
    val descriptorsMap = descriptors.map(d => (d.name, d)).toMap
    serviceGraph
      .get(entryPoint.name)
      .findPredecessor(dependency => !descriptorsMap.get(dependency.value).flatMap(_.healthy).getOrElse(false))
      .map(
        unhealthyDependency =>
          NotHealthyServiceDependencyError(
            serviceGraph
              .get(unhealthyDependency.value)
              .shortestPathTo(
                serviceGraph
                  .get(entryPoint.name)
              )(_.value)
              .map(x => x.edges.map(e => s"${e._2.value}~>${e._1.value}").toList.reverse.mkString(","))
          ).leftNec
      )
      .getOrElse(DeploymentMetada(descriptorsMap, sortedServices).rightNec)
  }

  private def createServicesTopologically(deploymentMetada: DeploymentMetada): Seq[Microservice] =
    deploymentMetada.sortedServices.flatMap(
      serviceName =>
        deploymentMetada.descriptors
          .get(serviceName)
          .toList
          .flatMap(
            serviceDesciptor =>
              (1 to serviceDesciptor.replicas).map(
                id =>
                  Microservice(
                    id,
                    serviceDesciptor.name,
                    serviceDesciptor.entryPoint.getOrElse(false),
                    serviceDesciptor.healthy.getOrElse(false),
                    serviceDesciptor.dependencies.getOrElse(Nil)
                  )
              )
          )
    )

  def execute(deploymentRequest: DeploymentRequest): ExecutionResult[Seq[Microservice]] =
    for {
      topology           <- buildTopologyGraph(deploymentRequest.descriptors)
      sortedServiceNames <- sortTopology(topology)
      singleEntryPoint   <- findSingleEntryPoint(deploymentRequest.descriptors)
      deploymentMetada   <- buildDeploymentMetadata(deploymentRequest.descriptors, topology, singleEntryPoint, sortedServiceNames)
    } yield createServicesTopologically(deploymentMetada)
}
