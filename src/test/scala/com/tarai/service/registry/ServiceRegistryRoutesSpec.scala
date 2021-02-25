package com.tarai.service.registry

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.{ RouteTestTimeout, ScalatestRouteTest }
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.duration.DurationInt

class ServiceRegistryRoutesSpec extends AnyWordSpec with Matchers with ScalaFutures with ScalatestRouteTest with PlayJsonSupport {
  lazy val testKit         = ActorTestKit()
  implicit def typedSystem = testKit.system
  implicit val timeout: RouteTestTimeout = RouteTestTimeout(2.seconds)

  override def createActorSystem(): akka.actor.ActorSystem =
    testKit.system.classicSystem

  val serviceRegistryActor = testKit.spawn(ServiceRegistryActor())
  lazy val routes          = new ServiceRegistryRoutes(serviceRegistryActor).deploymentRoutes
  "ServiceRegistryRoutes" should {
    "return no microservices if no present (GET /microservices)" in {
      val request = HttpRequest(uri = "/microservices")

      request ~> routes ~> check {
        status should ===(StatusCodes.OK)

        contentType should ===(ContentTypes.`application/json`)

        entityAs[GetMicroservicesResponse] shouldBe GetMicroservicesResponse()
      }
    }

    "successfully deploy new microservices with the valid config  (POST /microservices/deploy)" in {
      val serviceA = MicroserviceDescriptor(1, "A", Some(true), Some(List("B", "C")), Some(true))
      val serviceB = MicroserviceDescriptor(2, "B", None, Some(List("C", "D")), Some(true))
      val serviceC = MicroserviceDescriptor(2, "C", None, Some(List("D")), Some(true))
      val serviceD = MicroserviceDescriptor(2, "D", None, None, Some(true))

      // using the RequestBuilding DSL:
      val request = Post("/microservices/deploy", Seq(serviceD, serviceC, serviceA, serviceB))

      request ~> routes ~> check {
        status should ===(StatusCodes.OK)

        // we expect the response to be json:
        contentType should ===(ContentTypes.`application/json`)

        // and we know what message we're expecting back:
        entityAs[DeploymentResponse] shouldBe DeploymentResponse(
          Some(
            List(
              Microservice(1, "D", entryPoint = false, healthy = true, List()),
              Microservice(2, "D", entryPoint = false, healthy = true, List()),
              Microservice(1, "C", entryPoint = false, healthy = true, List("D")),
              Microservice(2, "C", entryPoint = false, healthy = true, List("D")),
              Microservice(1, "B", entryPoint = false, healthy = true, List("C", "D")),
              Microservice(2, "B", entryPoint = false, healthy = true, List("C", "D")),
              Microservice(1, "A", entryPoint = true, healthy  = true, List("B", "C"))
            )
          ),
          None
        )

      }
    }

    "fail to deploy when multiple entry points provided in the config  (POST /microservices/deploy)" in {
      val serviceA = MicroserviceDescriptor(1, "A", Some(true), Some(List("B", "C")), Some(true))
      val serviceB = MicroserviceDescriptor(2, "B", Some(true), Some(List("D")), Some(true))
      val serviceC = MicroserviceDescriptor(2, "C", None, Some(List("D")), Some(true))
      val serviceD = MicroserviceDescriptor(2, "D", None, None, Some(true))

      val request = Post("/microservices/deploy", Seq(serviceD, serviceC, serviceA, serviceB))

      request ~> routes ~> check {
        status should ===(StatusCodes.OK)

        contentType should ===(ContentTypes.`application/json`)

        entityAs[DeploymentResponse] shouldBe DeploymentResponse(None, Some(List(NotSingleEntryPointError(List("A", "B")))))
      }
    }

    "fail to deploy when unhealthy dependency provided in the config  (POST /microservices/deploy)" in {
      val serviceA = MicroserviceDescriptor(1, "A", Some(true), Some(List("B", "C")), Some(true))
      val serviceB = MicroserviceDescriptor(2, "B", None, Some(List("C", "D")), Some(true))
      val serviceC = MicroserviceDescriptor(2, "C", None, None, Some(true))
      val serviceD = MicroserviceDescriptor(2, "D", None, None, Some(false))

      val request = Post("/microservices/deploy", Seq(serviceD, serviceC, serviceA, serviceB))

      request ~> routes ~> check {
        status should ===(StatusCodes.OK)

        contentType should ===(ContentTypes.`application/json`)

        entityAs[DeploymentResponse] shouldBe
          DeploymentResponse(None, Some(List(NotHealthyServiceDependencyError("A~>B,B~>D"))))
      }
    }

    "fail to deploy when cyclic dependencies provided in the config  (POST /microservices/deploy)" in {
      val serviceA = MicroserviceDescriptor(1, "A", Some(true), Some(List("B", "C")), Some(true))
      val serviceB = MicroserviceDescriptor(2, "B", None, Some(List("D")), Some(true))
      val serviceC = MicroserviceDescriptor(2, "C", None, Some(List("D")), Some(true))
      val serviceD = MicroserviceDescriptor(2, "D", None, Some(List("A")), Some(true))

      val request = Post("/microservices/deploy", Seq(serviceD, serviceC, serviceA, serviceB))

      request ~> routes ~> check {
        status should ===(StatusCodes.OK)

        contentType should ===(ContentTypes.`application/json`)

        entityAs[DeploymentResponse] shouldBe
          DeploymentResponse(None, Some(List(CyclicDescriptorError("A~>D,D~>C,C~>A"))))
      }
    }
  }
}
