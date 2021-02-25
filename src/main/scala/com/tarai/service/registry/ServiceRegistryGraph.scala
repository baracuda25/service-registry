package com.tarai.service.registry
import cats.data.{ NonEmptyChain, NonEmptyList }
import cats.implicits._
import scala.collection.mutable

case class ServiceRegistryGraph(descriptors: Seq[MicroserviceDescriptor]) {

  type ExecutionResult[A] = Either[NonEmptyChain[DeploymentError], A]

  private lazy val _descriptorsStateMap: Map[String, MicroserviceDescriptor] = descriptors.map(d => (d.name, d)).toMap

  private lazy val _nodes: Seq[String] = descriptors
    .flatMap(descriptor => descriptor.dependencies.toList.flatten :+ descriptor.name)
    .distinct

  private lazy val edges: Seq[(String, String)] = descriptors
    .flatMap(descriptor => descriptor.dependencies.toList.flatten.map(dep => (dep, descriptor.name)))

  private lazy val _inboundGraph: Map[String, Seq[String]] = edges
    .groupBy(_._1)
    .map(v => (v._1, v._2.map(_._2)))

  private lazy val _outboundGraph: Map[String, Seq[String]] = edges
    .groupBy(_._2)
    .map(v => (v._1, v._2.map(_._1)))

  private lazy val _indegree: Map[String, Int] = _nodes.map((_, 0)).toMap ++ descriptors
          .map(descriptor => (descriptor.name, descriptor.dependencies.toList.flatten.size))
          .toMap

  private lazy val _cycle: Option[Seq[String]] = {

    object CycleFinder {

      def unapply(node: String): Option[Seq[String]] = findCycle(node, mutable.HashMap[String, Int](), List(node))

      private def findCycle(serviceName: String, v: mutable.Map[String, Int], path: Seq[String] = Nil): Option[Seq[String]] = {
        if (v.get(serviceName).contains(-1)) return Some(path)
        else if (v.get(serviceName).contains(1)) return None
        else {
          v.update(serviceName, -1)
          for (j <- _inboundGraph.getOrElse(serviceName, List[String]())) {
            val maybeCycle = findCycle(j, v, path :+ j)
            if (maybeCycle.isDefined) return maybeCycle
          }
          v.update(serviceName, 1)
        }
        None
      }
    }

    _nodes.collectFirst {
      case CycleFinder(path) => path.zip(path.tail).map(t => t._1 + "~>" + t._2)
    }

  }

  private lazy val _microservices: Seq[Microservice] =
    _topologicalSorting.flatMap(
      serviceName =>
        _descriptorsStateMap
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

  private def findPredecessorPath(serviceName: String, predicate: String => Boolean): Option[Seq[String]] = {
    def findPredecessor(serviceName: String, path: Seq[String] = Nil): Option[Seq[String]] = {
      if (predicate(serviceName)) return Some(path)
      else {
        for (j <- _outboundGraph.getOrElse(serviceName, List[String]())) {
          val maybePath = findPredecessor(j, path :+ j)
          if (maybePath.isDefined) return maybePath
        }
      }
      None
    }
    val option = findPredecessor(serviceName, List(serviceName))
    option.map(path => path.zip(path.tail).map(t => t._1 + "~>" + t._2))
  }

  private lazy val _topologicalSorting: List[String] = {

    val indegree: mutable.Map[String, Int] = mutable.Map(_indegree.toSeq: _*)

    val graph: mutable.Map[String, Seq[String]] = mutable.Map(_inboundGraph.toSeq: _*)

    val queue = mutable.Queue[String](indegree.filter(_._2 == 0).keys.toSeq: _*)

    val result = mutable.ListBuffer[String]()
    while (queue.nonEmpty) {
      val edge = queue.dequeue()
      result.addOne(edge)
      if (graph.contains(edge)) {
        graph(edge).foreach { child =>
          indegree.update(child, indegree(child) - 1)
          if (indegree(child) == 0)
            queue.enqueue(child)
        }
        graph.remove(edge)
      }
    }

    result.toList

  }

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

  private def createServices(entryPoint: MicroserviceDescriptor): ExecutionResult[Seq[Microservice]] =
    _cycle.map(cycle => CyclicDescriptorError(cycle.mkString(",")).leftNec).getOrElse {
      findPredecessorPath(entryPoint.name, dependency => !_descriptorsStateMap.get(dependency).flatMap(_.healthy).getOrElse(false))
        .map(unhealthyDependency => NotHealthyServiceDependencyError(unhealthyDependency.mkString(",")).leftNec)
        .getOrElse(_microservices.rightNec)
    }

  def createMicroservices: ExecutionResult[Seq[Microservice]] =
    for {
      singleEntryPoint <- findSingleEntryPoint(descriptors)
      microservices    <- createServices(singleEntryPoint)
    } yield microservices

}
