package fr.janalyse.sotohp.service

sealed trait ServiceIssue extends Exception

case class ServiceUserIssue(message: String)     extends Exception(message) with ServiceIssue
case class ServiceDatabaseIssue(message: String) extends Exception(message) with ServiceIssue
case class ServiceInternalIssue(message: String) extends Exception(message) with ServiceIssue

sealed trait ServiceStreamIssue extends Exception

case class ServiceStreamInternalIssue(message: String, throwable: Throwable) extends Exception(message, throwable) with ServiceStreamIssue
