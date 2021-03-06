package group.research.aging.cromwell.web.api.runners

import akka.actor._
import akka.pattern._
import akka.stream._
import cats.effect.{ContextShift, IO}
import group.research.aging.cromwell.client
import group.research.aging.cromwell.client.{CromwellClientAkka, QueryResult, QueryResults, StatusAndOutputs, StatusInfo, WorkflowStatus}
import group.research.aging.cromwell.web.Commands.TestRun
import group.research.aging.cromwell.web.WebServer.http
import group.research.aging.cromwell.web.api.runners.MessagesAPI.CallBack
import group.research.aging.cromwell.web.common.BasicActor
import group.research.aging.cromwell.web.{Commands, Results}
import hammock.akka.AkkaInterpreter
import hammock.circe.implicits._
import hammock.{Hammock, Method, _}
import io.circe.Json
import io.circe.syntax._

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Try


/**
  * Actors that interacts with cromwell server, receives messages from API and returns stuff back
  * @param client
  */
class RunnerWorker(client: CromwellClientAkka) extends BasicActor {

  debug(s"runner worker for ${client.base} cromwell server started!")

  import scala.concurrent.duration._

  val decider: Supervision.Decider = {
    case _: ArithmeticException ⇒ Supervision.Resume
    case _                      ⇒ Supervision.Restart
  }

  implicit def dispatcher: ExecutionContextExecutor = context.dispatcher

  context.system.scheduler.schedule(
    2 second,
    8 seconds,
    self,
    MessagesAPI.Poll)

  implicit val materializer: ActorMaterializer = ActorMaterializer( ActorMaterializerSettings(context.system).withSupervisionStrategy(decider))

  //implicit  protected def getInterpreter: Interpreter[IO] = Interpreter[IO]
  implicit val cs: ContextShift[IO] = IO.contextShift(http.system.dispatcher)

  implicit protected def getInterpreter: InterpTrans[IO] = AkkaInterpreter.instance[IO]

  /**
    * Recieve generating functions
    * @param callbacks callbacks to check for responses from servers
    * @return new Receive function
    */
  protected def operation(callbacks: Map[String, Set[MessagesAPI.CallBack]]): Receive = {


    case MessagesAPI.Poll => //checks running workflows and fires callbacks and batches
      //debug(s"polling the callbacks, currently ${callbacks.mkString(",")} are avaliable")
      val queryResults: QueryResults = client.getQuery().unsafeRunSync()
      /*
      val finished= queryResults.filter(s=>
        s.status ==  WorkflowStatus.Succeded.entryName ||
          s.status ==WorkflowStatus.WorkflowStatus.Failed.entryName).map(_.id)
      */
      context.parent ! MessagesAPI.ServerResults(client.base, queryResults)


      val toDelete: scala.Seq[(String, MessagesAPI.CallBack)] = runForDeletion(callbacks, queryResults.results)
      val g: Map[String, Set[CallBack]] = toDelete.groupBy(_._1).mapValues(_.map(_._2).toSet)
      if(g.nonEmpty || callbacks.contains(TestRun.id)) {
        if(callbacks.contains(TestRun.id)) testResponse(callbacks)
        val updCallbacks = callbacks.filter(c => c._1 != TestRun.id)
          .map{ case (i, cbs) => if(g.contains(i)) (i, cbs -- g(i)) else (i, cbs.filter(_!=CallBack.empty))}
          .filter(_._2.nonEmpty)

        //debug(s"deleting ${callbacks.size - updCallbacks.size} callbacks after execution!")
        context.become(operation(updCallbacks))
      }


    case mes @ MessagesAPI.ServerCommand(Commands.Run(wdl, input, options, dependencies), serverURL, _, _, _, _) =>
          val source: ActorRef = sender()
          val serv = if(serverURL.endsWith("/")) serverURL.dropRight(1) else serverURL
          val cl: CromwellClientAkka = if(client.base.contains(serv)) client else client.copy(base = serv)
          val statusUpdate = cl.postWorkflowStrings(wdl, input.replace("\t", "  "), options, dependencies)
          statusUpdate pipeTo source
          statusUpdate.map(s=>mes.promise(s)) pipeTo self

    case mes @ MessagesAPI.ServerCommand(Commands.TestRun(wdl, input, results, dependencies), serverURL, _, _, _, _) =>
      //test server command
      val source: ActorRef = sender()
      val statusUpdate: Future[StatusInfo] = Future{
        StatusInfo("e442e52a-9de1-47f0-8b4f-e6e565008cf1-TEST", "Submitted")
      }
      statusUpdate pipeTo source
      statusUpdate.map { s =>
        val p = mes.promise(s)
        //debug(s"prossesing TEST RUN with ${Set(results)}")
        p.copy(callbacks = p.callbacks.map(v => v.copy(updateOn = Set(results))))
      } pipeTo self


    case Commands.QueryWorkflows(status, subworkflows, _, _) => //here we do not have pagination at the moment
      val s = sender
      client.getAllMetadata(status, subworkflows).map(m=> Results.UpdatedMetadata(m.map(r=>r.id->r).toMap)).unsafeToFuture().pipeTo(s)

    case Commands.GetQuery(status, subs) =>
      val s = sender
      client.getQuery(status, subs).unsafeToFuture().pipeTo(s)

    case Commands.SingleWorkflow.GetOutput(id) =>
      val s = sender()
      val st = client.getStatus(id).unsafeToFuture()
      val o = client.getOutput(id).unsafeToFuture()
      val r: Future[StatusAndOutputs] = for{
        stat <-st
        out <- o
      } yield StatusAndOutputs(stat, out)
      r.pipeTo(s)

    case Commands.SingleWorkflow.GetStatus(id) =>
      val s = sender()
      client.getStatus(id).unsafeToFuture().pipeTo(s)

    case promise @ MessagesAPI.ServerPromise(status, backs) =>
      callbacks.get(status.id) match {
        case Some(cbs) => context.become(operation(callbacks.updated(status.id, cbs ++ backs)))
        case None => context.become(operation(callbacks + (status.id -> backs)))
      }

  }


  /**
    * Chooses callbacks that should fire and be cleaned
    * @param callbacks
    * @param results
    * @return
    */
  private def runForDeletion(callbacks: Map[String, Set[CallBack]], results: immutable.Seq[QueryResult]): Seq[(String, CallBack)] = {
    val toDelete: Seq[(String, CallBack)] = for {
      r <- results
      if callbacks.contains(r.id)
      cb: CallBack <- callbacks(r.id)
      if cb.updateOn.contains(r.status)
      outputs = client.getOutput(r.id).unsafeRunSync()
      json: Json = MessagesAPI.PipelineResult(r.id, r.status, outputs.outputs).asJson
      //debug(s"sending reesults back to ${cb.backURL}")
      headers = Map("Content-Type" -> "application/json") ++ cb.headers
    }
      yield {
        debug(s"sending reesults back to ${cb.backURL}")

        //val result: HttpResponse = resultTry.get
        //val result: HttpResponse = resultTry.get
        debug(s"calling back ${cb.backURL} with request:")
        debug(json)
        debug("and result")
        //WARNING EFFECT!!!
        val resultTry = Try {
          val req = Hammock.request[Json](Method.POST, Uri.unsafeParse(s"${cb.backURL}"), headers, Some(json))
          req.exec[IO].unsafeRunSync()
        }
        debug(resultTry)
        debug("with headers = " + headers.mkString(";"))
        debug(s"deleting callback ${cb.backURL} from the list")
        r.id -> (resultTry match {
          case scala.util.Success(h) => cb
          case scala.util.Failure(th) =>
            error(
              s"""
                 |Failed to run ${cb.backURL} callback with the following error:
                 |${th}
              """.stripMargin)
            CallBack.empty
        })
      }
    toDelete
  }

  /**
    * Method to process only test responses
    *
    * @param callbacks
    */
  protected def testResponse(callbacks: Map[String, Set[MessagesAPI.CallBack]]): Unit = {
    for {
      cbs <- callbacks.get(TestRun.id)
      cb <- cbs
      if cb != CallBack.empty
    } {
      val headers = Map("Content-Type" -> "application/json") ++ cb.headers
      import io.circe._
      import io.circe.parser._
      val res: Option[Json] = parse(cb.updateOn.head).right.map(j => Some(j)).getOrElse(None)
      val u = Uri.unsafeParse(cb.backURL)
      debug(s"SENDING TEST RESULT TO ${u}:\n ${res}")
      Try{
        val req = Hammock.request[Json](Method.POST, u, Map("Content-Type" -> "application/json"), res)
        req.exec[IO].unsafeRunSync()

      } match {
        case  scala.util.Failure(e) => error(s"failed to callback to ${cb.backURL} with the following error ${e}")
        case scala.util.Success(result) =>debug(s"calling back ${cb.backURL} with request:")
          println("RESULT RECEIVED: " + result.toString)
          debug("with headers = " + headers.mkString(";"))
      }

    }
  }

  override def receive: Receive = operation(Map.empty)
}
