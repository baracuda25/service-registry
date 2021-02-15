package com.tarai.service

import akka.actor.typed.ActorRef
import play.api.libs.json.{ Json, OFormat }

package object registry {

  final case class MicroserviceDescriptor(replicas:     Int,
                                          name:         String,
                                          entryPoint:   Option[Boolean],
                                          dependencies: Option[Seq[String]],
                                          healthy:      Option[Boolean])

  object MicroserviceDescriptor {
    implicit val format: OFormat[MicroserviceDescriptor] = Json.format[MicroserviceDescriptor]
  }

  final case class Microservice(id: Int, name: String, entryPoint: Boolean, healthy: Boolean, dependencies: Seq[String])

  object Microservice {
    implicit val format: OFormat[Microservice] = Json.format[Microservice]
  }

  sealed trait DeploymentError

  object DeploymentError {
    implicit val format: OFormat[DeploymentError] = Json.format[DeploymentError]
  }

  case class CyclicDescriptorError(cycle: String) extends DeploymentError

  object CyclicDescriptorError {
    implicit val format: OFormat[CyclicDescriptorError] = Json.format[CyclicDescriptorError]
  }

  case class NotHealthyServiceDependencyError(dependencyPath: Option[String]) extends DeploymentError

  object NotHealthyServiceDependencyError {
    implicit val format: OFormat[NotHealthyServiceDependencyError] = Json.format[NotHealthyServiceDependencyError]
  }

  case class NotSingleEntryPointError(entryPoints: Seq[String]) extends DeploymentError

  object NotSingleEntryPointError {
    implicit val format: OFormat[NotSingleEntryPointError] = Json.format[NotSingleEntryPointError]
  }

  sealed trait Command

  final case class DeploymentRequest(descriptors: Seq[MicroserviceDescriptor], replyTo: ActorRef[DeploymentResponse]) extends Command

  final case class DeploymentResponse(microservices: Option[Seq[Microservice]] = None, errors: Option[List[DeploymentError]] = None)

  object DeploymentResponse {
    implicit val format: OFormat[DeploymentResponse] = Json.format[DeploymentResponse]
  }

  final case class GetMicroservicesRequest(replyTo:        ActorRef[GetMicroservicesResponse]) extends Command
  final case class GetMicroservicesResponse(microservices: Option[Seq[Microservice]] = None) extends Command

  object GetMicroservicesResponse {
    implicit val format: OFormat[GetMicroservicesResponse] = Json.format[GetMicroservicesResponse]
  }

}
