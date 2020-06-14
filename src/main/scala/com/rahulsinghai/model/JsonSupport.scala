package com.rahulsinghai.model

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val instanceToCreateJsonFormat: RootJsonFormat[InstanceToCreate] = jsonFormat8(InstanceToCreate)

  implicit val instancesToCreateJsonFormat: RootJsonFormat[InstancesToCreate] = jsonFormat1(InstancesToCreate)

  implicit val createEC2InstanceResponseJsonFormat: RootJsonFormat[CreateEC2InstanceResponse] = jsonFormat2(CreateEC2InstanceResponse)

  implicit val actionPerformedJsonFormat: RootJsonFormat[ActionPerformed] = jsonFormat1(ActionPerformed)
  /*
    // format that discriminates based on an additional field "type" that can either be "Cat" or "Dog"
    implicit val summaryRowJsonFormat: RootJsonFormat[SummaryRow] = new RootJsonFormat[SummaryRow] {
      def write(obj: SummaryRow): JsValue =
        JsObject((obj match {
          case summary: TLLSummaryRow             => summary.toJson
          case summary: AnaMIOpsSummaryRow        => summary.toJson
          case summary: ImpalaUserLevelSummaryRow => summary.toJson
          case summary: ImpalaBULevelSummaryRow   => summary.toJson
        }).asJsObject.fields)

      def read(json: JsValue): SummaryRow =
        json.asJsObject.getFields("summaryType") match {
          case Seq(JsString("analytics_mi_ops_cluster_level"))                   => json.convertTo[AnaMIOpsSummaryRow]
          case Seq(JsString("analytics_mi_ops_cluster_level_weekly_difference")) => json.convertTo[AnaMIOpsSummaryRow]
          case Seq(JsString("analytics_mi_ops_domain_level"))                    => json.convertTo[AnaMIOpsSummaryRow]
          case Seq(JsString("analytics_mi_ops_domain_level_weekly_difference"))  => json.convertTo[AnaMIOpsSummaryRow]
          case Seq(JsString("impala_user_level_daily"))                          => json.convertTo[ImpalaUserLevelSummaryRow]
          case Seq(JsString("impala_bu_level_weekly"))                           => json.convertTo[ImpalaBULevelSummaryRow]
          case Seq(JsString("cluster_level"))                                    => json.convertTo[TLLSummaryRow]
          case Seq(JsString("cluster_level_weekly_difference"))                  => json.convertTo[TLLSummaryRow]
          case Seq(JsString("program_level"))                                    => json.convertTo[TLLSummaryRow]
          case Seq(JsString("program_level_weekly_difference"))                  => json.convertTo[TLLSummaryRow]
          case _                                                                 => deserializationError("Valid JSON summary expected!")
        }
     }
   */
}
