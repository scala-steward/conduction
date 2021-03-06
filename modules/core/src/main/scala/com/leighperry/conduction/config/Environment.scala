package com.leighperry.conduction.config

import java.io.{ File, FileInputStream }
import java.util.Properties

import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{ Applicative, Monad }

trait Environment[F[_]] {
  def get(key: Environment.Key): F[Option[String]]
}

object Environment {
  type Key = String

  def fromEnvVars[F[_]: Sync]: F[Environment[F]] =
    Sync[F]
      .delay(sys.env)
      .map(fromMap(_))

  def fromPropertiesFile[F[_]: Sync](filepath: String): F[Environment[F]] =
    Sync[F].bracket(new FileInputStream(new File(filepath)).pure[F]) {
      stream =>
        Sync[F].delay {
          val prop = new Properties()
          prop.load(stream) // thanks, jdk

          new Environment[F] {
            override def get(key: Environment.Key): F[Option[String]] =
              Option(prop.get(key))
                .map(_.asInstanceOf[String]) // thanks, jdk
                .pure[F]
          }
        }
    }(_.close().pure[F])

  def fromMap[F[_]: Applicative](map: Map[String, String]): Environment[F] =
    new Environment[F] {
      override def get(key: Environment.Key): F[Option[String]] =
        map
          .get(key)
          .pure[F]
    }

  /**
   * Configuration derived from arguments of the form --key=value. Useful for Scio / Beam configuration, where
   * configuration has to be done via the command line args.
   */
  def fromArgs[F[_]: Applicative](args: List[String]): Environment[F] =
    fromMap(
      args
        .filter(s => s.startsWith("--") && s.contains("="))
        .map(s => s.substring(2).split('=').toList)
        .flatMap {
          _ match {
            case s1 :: s2 :: Nil => List((s1, s2))
            case _ => Nil // ignored
          }
        }
        .toMap
    )

  def printer[F[_]: Applicative]: String => F[Unit] =
    (s: String) => println(s).pure[F]

  def silencer[F[_]: Applicative]: String => F[Unit] =
    _ => ().pure[F]

  def logging[F[_]: Monad](inner: Environment[F], log: String => F[Unit]): Environment[F] =
    new Environment[F] {
      override def get(key: Environment.Key): F[Option[String]] =
        for {
          value <- inner.get(key)
          _ <- value.fold(log(s"Not configured: $key"))(v => log(s"$key => $v"))
        } yield value
    }
}
