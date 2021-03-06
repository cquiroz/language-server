package scala.meta.languageserver.providers

import com.typesafe.scalalogging.LazyLogging
import langserver.{types => l}
import langserver.messages.DocumentHighlightResult
import scala.meta.languageserver.Uri
import scala.meta.languageserver.search.SymbolIndex
import scala.meta.languageserver.ScalametaEnrichments._

object DocumentHighlightProvider extends LazyLogging {

  def highlight(
      symbolIndex: SymbolIndex,
      uri: Uri,
      position: l.Position
  ): DocumentHighlightResult = {
    logger.info(s"Document highlight in $uri")
    val locations = for {
      symbol <- symbolIndex
        .findSymbol(uri, position.line, position.character)
        .toList
      data <- symbolIndex.referencesData(symbol)
      _ = logger.info(s"Highlighting symbol `${data.name}: ${data.signature}`")
      pos <- data.referencePositions(withDefinition = true)
      if pos.uri == uri.value
      _ = logger.debug(s"Found highlight at [${pos.range.get.pretty}]")
    } yield pos.toLocation
    // TODO(alexey) add DocumentHighlightKind: Text (default), Read, Write
    DocumentHighlightResult(locations)
  }

}
