package com.rahulsinghai.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.amazonaws.services.ec2.model.InstanceType
import com.rahulsinghai.actor.EC2Actor._
import com.rahulsinghai.actor.ImageBuilderActor.AMIActionPerformed
import com.rahulsinghai.actor.SubnetActor.SubnetSuccessResponse
import com.rahulsinghai.actor.VPCActor.VPCSuccessResponse
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val instanceTypeJsonFormat: RootJsonFormat[InstanceType] = new RootJsonFormat[InstanceType] {
    def write(obj: InstanceType): JsValue = JsString(obj.toString)

    def read(json: JsValue): InstanceType = InstanceType.fromValue(json.toString)
  }

  implicit val instanceToCreateJsonFormat: RootJsonFormat[InstanceToCreate] = jsonFormat8(InstanceToCreate)

  implicit val instancesToCreateJsonFormat: RootJsonFormat[InstancesToCreate] = jsonFormat1(InstancesToCreate)

  implicit val amiActionPerformedJsonFormat: RootJsonFormat[AMIActionPerformed] = jsonFormat1(AMIActionPerformed)

  implicit val ec2SuccessResponseJsonFormat: RootJsonFormat[EC2SuccessResponse] = jsonFormat1(EC2SuccessResponse)

  implicit val vpcSuccessResponseJsonFormat: RootJsonFormat[VPCSuccessResponse] = jsonFormat1(VPCSuccessResponse)

  implicit val subnetSuccessResponseJsonFormat: RootJsonFormat[SubnetSuccessResponse] = jsonFormat1(SubnetSuccessResponse)
}
