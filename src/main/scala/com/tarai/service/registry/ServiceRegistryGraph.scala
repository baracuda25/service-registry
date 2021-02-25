package com.tarai.service.registry
import cats.data.{ NonEmptyChain, NonEmptyList }
import cats.implicits._
import scala.collection.mutable

case class ServiceRegistryGraph(descriptors: Seq[MicroserviceDescriptor]) {

  type ExecutionResult[A] = Either[NonEmptyChain[DeploymentError], A]

  private lazy val _descriptorsStateMap: Map[String, MicroserviceDescriptor] = descriptors.map(d => (d.name, d)).toMap

  private lazy val _outboundEdges: Seq[(String, String)] = descriptors
    .flatMap(descriptor => descriptor.dependencies.toList.flatten.map(dep => (dep, descriptor.name)))

  private lazy val _inboundEdges: Seq[(String, String)] = _outboundEdges.map(_.swap)

  private lazy val _nodes: Seq[String] = _inboundEdges.flatMap(_.toList).distinct

  private lazy val _outboundGraph: Map[String, Seq[String]] = graph(_outboundEdges)

  private lazy val _inboundGraph: Map[String, Seq[String]] = graph(_inboundEdges)

  private lazy val _indegree: Map[String, Int] = _nodes.map((_, 0)).toMap ++ _inboundGraph.view.mapValues(_.size)

  private lazy val _cycle: Option[Seq[String]] = {

    object CycleFinder {

      private val DISCOVERING = -1
      private val DISCOVERED  = 1

      def unapply(node: String): Option[Seq[String]] = findCycle(node, mutable.HashMap[String, Int](), List(node))

      private def findCycle(serviceName: String, v: mutable.Map[String, Int], path: Seq[String] = Nil): Option[Seq[String]] =
        v.get(serviceName)
          .collect {
            case DISCOVERING => Some(path)
            case DISCOVERED  => None
          }
          .flatten
          .orElse {
            v.update(serviceName, DISCOVERING)
            val maybeCycle = _outboundGraph
              .getOrElse(serviceName, Nil)
              .toList
              .collectFirstSome(child => findCycle(child, v, path :+ child))
            v.update(serviceName, DISCOVERED)
            maybeCycle
          }
    }

    _nodes.collectFirst {
      case CycleFinder(path) => stringifyPath(path)
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

  private def graph(edges: Seq[(String, String)]) =
    edges
      .groupBy(_._1)
      .map(v => (v._1, v._2.map(_._2)))

  private def findPredecessorPath(serviceName: String, predicate: String => Boolean): Option[Seq[String]] = {
    def findPredecessor(serviceName: String, path: Seq[String] = Nil): Option[Seq[String]] =
      if (predicate(serviceName)) Some(path)
      else {
        _inboundGraph
          .getOrElse(serviceName, Nil)
          .toList
          .collectFirstSome(child => findPredecessor(child, path :+ child))
      }
    findPredecessor(serviceName, List(serviceName)).map(stringifyPath)
  }

  private def stringifyPath(path: Seq[String]) = path.zip(path.tail).map(t => s"${t._1}~>${t._2}")

  private lazy val _topologicalSorting: List[String] = {

    val indegree: mutable.Map[String, Int] = mutable.Map(_indegree.toSeq: _*)

    val graph: mutable.Map[String, Seq[String]] = mutable.Map(_outboundGraph.toSeq: _*)

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
