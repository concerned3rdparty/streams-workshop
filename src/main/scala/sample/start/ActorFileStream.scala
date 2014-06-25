package sample.start

import akka.actor.ActorSystem
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.util.Timeout
import org.reactivestreams.api.Producer
import akka.stream.scaladsl.Flow
import java.io.{FileOutputStream, PrintWriter}
import scala.util.{Failure, Success, Try}
import org.reactivestreams.spi.{Subscription, Subscriber, Publisher}

object ActorFileStream {

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem("test")
    val settings = MaterializerSettings()
    implicit val materializer = FlowMaterializer(settings)

    val chooseFlowTest: Int = args(0).toInt

    if (chooseFlowTest == 1)
      runBasicFlow()
    else if (chooseFlowTest == 2)
      runComplexFlow()

    System.out.println(s"Finished Processing")

  }

  def runBasicFlow()(implicit system: ActorSystem, materializer: FlowMaterializer) = {

    var count = 0L
    val fileProducer: Producer[String] = new BasicFileProducer

    Flow(fileProducer).foreach { nextLine =>
      count += 1
      println(s"$count : $nextLine ")
    }.onComplete(materializer)(handleOnComplete)
  }

  def runComplexFlow()(implicit system: ActorSystem, materializer: FlowMaterializer) = {

    val fileProducer: Producer[String] = new BasicFileProducer

    var count = 0L

    //Process the file as a stream, spliting the stream on the name of the book
    //each book will be written out to a separate file, with the line count and the text of the verse
    Flow(fileProducer)
      .map {
      //split into fields: book|chapter|verse|text
      line => line.split("\\s*\\|\\s*")
    }.
      groupBy {
      //group by the book of each line
      line => line.head
    }.
      foreach {
      //process each line using a partial function
      count += 1
      processLine(materializer, count)
    }.
      onComplete(materializer)(handleOnComplete)
  }

  def runComplexFlow2()(implicit system: ActorSystem, materializer: FlowMaterializer) = {

    val fileProducer: Producer[String] = new BasicFileProducer

    var count = 0L

    //Process the file as a stream, spliting the stream on the name of the book
    //each book will be written out to a separate file, with the line count and the text of the verse
    Flow(fileProducer)
      .map {
      //split into fields: book|chapter|verse|text
      line => line.split("\\s*\\|\\s*")
    }.
      groupBy {
      //group by the book of each line
      line => line.head
    }.
      foreach {
      //process each line using a partial function
      count += 1
      processLine(materializer, count)
    }.
      onComplete(materializer)(handleOnComplete)
  }



  def processLine(materializer: FlowMaterializer, count:Long): PartialFunction[(String, Producer[Array[String]]), Unit] = {
    case (book, line) =>
      val output = new PrintWriter(new FileOutputStream(s"target/$book.txt"), true)
      Flow(line).
        //only print out the text of the verse and the line count
        foreach(line => {
          output.println(s"$count:${line.last}")
        }).
        // close resource when the group stream is completed
        onComplete(materializer)(_ => Try(output.close()))
  }

  def handleOnComplete(implicit system: ActorSystem): PartialFunction[Try[Unit], Unit] = {
    case Success(_) => system.shutdown()
    case Failure(e) =>
      println("Failure: " + e.getMessage)
      system.shutdown()
  }

}

