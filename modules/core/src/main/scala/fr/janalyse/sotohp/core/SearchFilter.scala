package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.FileLastModified

trait SearchFilter {
  def fileLastModifiedCriteria: FileLastModified => Boolean = _ => true
}
