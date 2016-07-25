package com.mnubo.dbevolv.util

import org.slf4j.LoggerFactory

trait Logging {
  protected val log =
    LoggerFactory.getLogger(getClass.getName)
}