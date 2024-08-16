package fr.janalyse.sotohp.config

case class SotohpConfigIssue(message: String, exception: Throwable) extends Exception(message, exception)