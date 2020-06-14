package com.rahulsinghai.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import com.amazonaws.services.ec2.model.InstanceType
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val instanceTypeJsonFormat: RootJsonFormat[InstanceType] = new RootJsonFormat[InstanceType] {
    def write(obj: InstanceType): JsValue = JsString(obj.toString)

    def read(json: JsValue): InstanceType = InstanceType.fromValue(json.toString)
   }

  implicit val instanceToCreateJsonFormat: RootJsonFormat[InstanceToCreate] = jsonFormat8(InstanceToCreate)

  implicit val instancesToCreateJsonFormat: RootJsonFormat[InstancesToCreate] = jsonFormat1(InstancesToCreate)

  implicit val createEC2InstanceResponseJsonFormat: RootJsonFormat[CreateEC2InstanceResponse] = jsonFormat2(CreateEC2InstanceResponse)

  implicit val actionPerformedJsonFormat: RootJsonFormat[ActionPerformed] = jsonFormat1(ActionPerformed)
}
