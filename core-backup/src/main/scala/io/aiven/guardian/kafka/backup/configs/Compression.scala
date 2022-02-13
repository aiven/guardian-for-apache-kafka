package io.aiven.guardian.kafka.backup.configs

sealed trait Compression

final case class Gzip(level: Option[Int]) extends Compression
