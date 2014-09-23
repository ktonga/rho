package org.http4s.rho.bits

import org.http4s.{Request, Status, Writable, MediaType}
import org.http4s.rho.Result

import scala.reflect.runtime.universe.TypeTag
import scalaz.\/
import scalaz.concurrent.Task


trait ResultMatcher[R] {
  def encodings: Set[MediaType]
  def resultInfo: Set[ResultInfo]
  def conv(req: Request, r: R): Task[Result[_, _]]
}

object ResultMatcher extends Level0Impls {
  implicit def resultMatcher[S, O](implicit s: TypeTag[S], o: TypeTag[O], w: Writable[O]): ResultMatcher[Result[S, O]] =
    new ResultMatcher[Result[S, O]] {
      override val encodings: Set[MediaType] = w.contentType.toSet
      override def conv(req: Request, r: Result[S, O]): Task[Result[_, _]] = Task.now(r)

      override val resultInfo: Set[ResultInfo] = ResultInfo.getStatus(s.tpe.dealias) match {
        case Some(status) => Set(StatusAndModel(status, o.tpe.dealias))
        case None         => Set(ModelOnly(o.tpe.dealias))
      }
    }

  implicit def optionMatcher[O](implicit o: TypeTag[O], w: Writable[O]) = new ResultMatcher[Option[O]] {
    override val encodings: Set[MediaType] = w.contentType.toSet
    override val resultInfo: Set[ResultInfo] = Set(StatusAndModel(Status.Ok, o.tpe.dealias),
                                                   StatusOnly(Status.NotFound))
    override def conv(req: Request, r: Option[O]): Task[Result[_, _]] = r match {
      case Some(r) => ResponseGeneratorInstances.Ok(r)
      case None    => ResponseGeneratorInstances.NotFound(req.uri.path)
    }
  }

  implicit def disjunctionMatcher[O1, O2](implicit r1: ResultMatcher[O1], r2: ResultMatcher[O2]): ResultMatcher[O1\/O2] =
  new ResultMatcher[O1\/O2] {
    override val encodings: Set[MediaType] = r1.encodings ++ r2.encodings
    override val resultInfo: Set[ResultInfo] = r1.resultInfo ++ r2.resultInfo
    override def conv(req: Request, r: \/[O1, O2]): Task[Result[_, _]] = r.fold(r1.conv(req, _), r2.conv(req, _))
  }

  implicit def writableMatcher[O](implicit o: TypeTag[O], w: Writable[O]) = new ResultMatcher[O] {
    override def encodings: Set[MediaType] = w.contentType.toSet
    override def resultInfo: Set[ResultInfo] = Set(StatusAndModel(Status.Ok, o.tpe.dealias))
    override def conv(req: Request, r: O): Task[Result[Status.Ok.type, O]] = ResponseGeneratorInstances.Ok(r)
  }
}


trait Level0Impls {
  implicit def taskMatcher[R](implicit r: ResultMatcher[R]): ResultMatcher[Task[R]] = new ResultMatcher[Task[R]] {
    override def encodings: Set[MediaType] = r.encodings
    override def resultInfo: Set[ResultInfo] = r.resultInfo
    override def conv(req: Request, t: Task[R]): Task[Result[_, _]] = t.flatMap(r.conv(req, _))
  }
}