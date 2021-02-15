package com.tarai.service.registry

import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout

import scala.concurrent.Future
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport

class ServiceRegistryRoutes(serviceRegistryActor: ActorRef[Command])(implicit val system: ActorSystem[_]) extends PlayJsonSupport {

  private implicit val timeout: Timeout = Timeout.create(system.settings.config.getDuration("app.routes.ask-timeout"))

  def deploy(services: Seq[MicroserviceDescriptor]): Future[DeploymentResponse] =
    serviceRegistryActor.ask(DeploymentRequest(services, _))

  def microservices: Future[GetMicroservicesResponse] =
    serviceRegistryActor.ask(GetMicroservicesRequest(_))

  val deploymentRoutes: Route = pathPrefix("microservices") {
    pathPrefix("deploy") {
      pathEnd {
        post {
          entity(as[Seq[MicroserviceDescriptor]]) { microserviceDescriptors =>
            onSuccess(deploy(microserviceDescriptors)) { response =>
              complete(StatusCodes.OK, response)
            }
          }
        }
      }
    } ~ pathEnd {
      get {
        onSuccess(microservices) { response =>
          complete(StatusCodes.OK, response)
        }
      }
    }
  }
}
