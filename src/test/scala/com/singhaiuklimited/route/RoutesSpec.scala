package com.singhaiuklimited.route

import akka.actor.typed
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.rahulsinghai.actor.ImageBuilderActor
import com.rahulsinghai.actor.ImageBuilderActor.{ AMISuccessResponse, ListImages }
import org.scalatest.wordspec.AnyWordSpecLike

class RoutesSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  //override lazy val testKit: ActorTestKit = ActorTestKit()
  implicit def typedSystem: typed.ActorSystem[Nothing] = testKit.system
  //override def createActorSystem(): akka.actor.ActorSystem = testKit.system.classicSystem

  "An ImageBuilderActor" must {
    //#test
    "reply to greeted" in {
      // Here we need to implement all the abstract members of Routes.
      // We "mock" actors by using a TestProbe created with testKit.createTestProbe()
      val imageBuilderActorProbe = createTestProbe[AMISuccessResponse]()
      val underTest = spawn(ImageBuilderActor())
      underTest ! ListImages("Self", imageBuilderActorProbe.ref)
      //imageBuilderActorProbe.expectMessage(AMISuccessResponse("Santa", underTest.ref))
      /*val ec2Actor: ActorRef[EC2Actor.EC2Command] = testKit.spawn(EC2Actor())
  val vpcActor: ActorRef[VPCActor.VPCCommand] = testKit.spawn(VPCActor())
  val subnetActor: ActorRef[SubnetActor.SubnetCommand] = testKit.spawn(SubnetActor())
  lazy val routes: Route = new Routes(imageBuilderActor, ec2Actor, vpcActor, subnetActor).routes
*/
    }
  }
}
