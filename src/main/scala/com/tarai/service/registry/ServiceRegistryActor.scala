package com.tarai.service.registry

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import cats.implicits._

object ServiceRegistryActor {

  def apply(): Behavior[Command] = registry(None)

  private def registry(microservices: Option[Seq[Microservice]]): Behavior[Command] =
    Behaviors.receiveMessage {
      case r: DeploymentRequest =>
        val value = DeploymentRequestExecutor.execute(r)
        val deploymentResponse = value.fold(
          errors   => DeploymentResponse(errors        = errors.toList.some),
          services => DeploymentResponse(microservices = services.some)
        )
        r.replyTo ! deploymentResponse
        registry(deploymentResponse.microservices)
      case GetMicroservicesRequest(replyTo) =>
        replyTo ! GetMicroservicesResponse(microservices)
        Behaviors.same
    }
}
