package group.research.aging.cromwell.web.communication


import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.http.scaladsl.HttpExt
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl._
import group.research.aging.cromwell.client.{CromwellClient, CromwellClientAkka}
import group.research.aging.cromwell.web.KeepAlive
import io.circe.parser.decode
import wvlet.log.LogFormatter.SourceCodeLogFormatter
import wvlet.log.{LogSupport, Logger}

/**
  * The class that handlers websocker interactions
  * @param http
  */
class WebsocketServer(implicit http: HttpExt, materializer: ActorMaterializer ) extends LogSupport{

  // Set the default log formatter
  Logger.setDefaultFormatter(SourceCodeLogFormatter)

  info(s"websocket server initialized")

  import scala.concurrent.duration._

  val route =  path("ws" / Remaining) { username: String =>
    handleWebSocketMessages(wsUser(username))
  }

  def wsUser(username: String): Flow[Message, Message, NotUsed] = {
    // Create an actor for every WebSocket connection, this will represent the contact point to reach the user
    //val wsUser: ActorRef = system.actorOf(WebSocketUser.props(username))

    info(s"adding user ${username}")
    val serverURL = CromwellClient.defaultURL
    val client  = CromwellClientAkka(serverURL, "v1")
    val wsUser: ActorRef = http.system.actorOf(Props(UserActor(username, client)), name = username)


    // Integration point between Akka Streams and the above actor
    val sink: Sink[Message, NotUsed] =
      Flow[Message]
        .collect { case TextMessage.Strict(json) =>
          //debug("Received:\n" + json)
          decode[WebsocketMessages.WebsocketMessage](json)
        }
        .filter(_.isRight)
        .map(_.right.get)
        .to(Sink.actorRef(wsUser, WebsocketMessages.WsHandleDropped)) // connect to the wsUser Actor

    // Integration point between Akka Streams and above actor
    val source: Source[Message, NotUsed] =
      Source
        .actorRef(bufferSize = 20480, overflowStrategy = OverflowStrategy.fail)
        .map{ c: WebsocketMessages.WebsocketMessage =>
          import io.circe.syntax._
          val js = c.asJson
          //debug("Sent:\n" + js.toString())
          TextMessage.Strict(js.toString())
        }
        .mapMaterializedValue { wsHandle =>
          // the wsHandle is the way to talk back to the user, our wsUser actor needs to know about this to send
          // messages to the WebSocket user
          val connection = WebsocketMessages.ConnectWsHandle(wsHandle)
          debug("Connection:\n" + connection)
          wsUser ! WebsocketMessages.ConnectWsHandle(wsHandle)
          // don't expose the wsHandle anymore
          NotUsed
        }
        .keepAlive(maxIdle = 30.seconds, () => {
          import io.circe.syntax._
          val m: WebsocketMessages.WebsocketMessage = WebsocketMessages.WebsocketAction(KeepAlive.web)//
          val js = m.asJson
          //debug("Sent:\n" + js.toString())
          TextMessage.Strict(js.toString())
          }
        )
    Flow.fromSinkAndSource(sink, source)
  }

  val websocketRoute: Route = path("ws" / Remaining) { username: String =>
    handleWebSocketMessages(wsUser(username))
  }

}
