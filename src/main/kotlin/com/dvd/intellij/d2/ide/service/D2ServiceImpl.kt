package com.dvd.intellij.d2.ide.service

import com.dvd.intellij.d2.components.D2Layout
import com.dvd.intellij.d2.ide.action.ConversionOutput
import com.dvd.intellij.d2.ide.editor.images.D2SvgViewer
import com.dvd.intellij.d2.ide.editor.images.D2_FILE_LAYOUT
import com.dvd.intellij.d2.ide.editor.images.D2_FILE_THEME
import com.dvd.intellij.d2.ide.execution.D2Command
import com.dvd.intellij.d2.ide.execution.D2CommandOutput
import com.dvd.intellij.d2.ide.format.D2FormatterResult
import com.intellij.execution.process.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis

private val LOG = logger<D2ServiceImpl>()

private class D2ServiceImpl : D2Service, Disposable {
  private val _map: MutableMap<FileEditor, D2CommandOutput.Generate> = HashMap()

  override val map: Map<FileEditor, D2CommandOutput.Generate> = _map

  override fun getCompilerVersion(): String? = simpleRun(D2Command.Version)?.version

  override fun isCompilerInstalled() = getCompilerVersion() != null

  override fun getLayoutEngines(): List<D2Layout>? = simpleRun(D2Command.LayoutEngines)?.layouts

  override fun compile(fileEditor: FileEditor) {
    val oldExec = _map[fileEditor]
    val oldCommand = oldExec?.command

    val theme = fileEditor.getUserData(D2_FILE_THEME)
    val layout = fileEditor.getUserData(D2_FILE_LAYOUT)
    val command = if (oldCommand == null) {
      // find a free port
      val port = ServerSocket(0).use {
        it.localPort
      }

      val targetFile = Files.createTempFile("d2_temp_svg", ".svg")
      D2Command.Generate(input = fileEditor.file, targetFile = targetFile, port = port, theme = theme, layout = layout)
    } else {
      oldCommand.copy(theme = theme, layout = layout)
    }

    oldCommand?.process?.let {
      it.destroyProcess()
      @Suppress("ControlFlowWithEmptyBody")
      val terminationTime = measureTimeMillis {
        // background process? ~5ms
        while (!it.isProcessTerminated) {
        }
      }
      "[plugin ] [info] D2 process termination ${terminationTime}ms".let { message ->
        _map[fileEditor] = _map[fileEditor]?.appendLog(message) ?: return
        LOG.info(message)
      }
    }
    command.process = prepare(command, object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        onTextAvailable(fileEditor = fileEditor, event = event, outputType = outputType, command = command)
      }

      override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
        deleteFile(command)
      }
    })

    @Suppress("IfThenToElvis")
    _map[fileEditor] = if (oldExec == null) {
      command.parseOutput("[plugin ] info: starting process...\n")
    } else {
      oldExec.copy(command = command).appendLog("[plugin ] info: restarting process...\n")
    }
    command.process?.startNotify()

    (fileEditor as D2SvgViewer).refreshD2(command.port)
  }

  override fun closeFile(fileEditor: FileEditor) {
    fileEditor.putUserData(D2_FILE_LAYOUT, null)
    fileEditor.putUserData(D2_FILE_THEME, null)

    _map[fileEditor]?.command?.process?.destroyProcess()
    _map -= fileEditor

    LOG.info("[plugin] Closed file")
  }

  override fun format(file: File): D2FormatterResult {
    val out = simpleRun(D2Command.Format(file))?.content
    return when {
      out == null -> D2FormatterResult.Error("Unknown error")
      out.contains("err: failed") -> D2FormatterResult.Error(out)
      else -> D2FormatterResult.Success(out)
    }
  }

  override fun convert(file: Path, format: ConversionOutput): ByteArray {
    if (format == ConversionOutput.SVG) {
      return Files.readAllBytes(file)
    }

    val input = Files.newInputStream(file).use { ImageIO.read(it) }
    val ext = when (format) {
      ConversionOutput.PNG -> "png"
      ConversionOutput.JPG -> "jpeg"
      ConversionOutput.TIFF -> "tiff"
      else -> {
        error("cannot be")
      }
    }
    val out = BufferExposingByteArrayOutputStream()
    ImageIO.write(input, ext, out)
    return out.toByteArray()
  }

  private fun onTextAvailable(fileEditor: FileEditor, event: ProcessEvent, outputType: Key<*>, command: D2Command.Generate) {
    buildString {
      append("[process] ")
      if (outputType == ProcessOutputType.SYSTEM) {
        append("info: ")
        append(event.text)
      } else {
        // remove timestamp
        append(event.text.replace(Regex("\\[?\\d{2}:\\d{2}:\\d{2}]? "), ""))
      }
    }.let {
      LOG.info(it)

      // null if file editor closed
      _map[fileEditor] = _map[fileEditor]?.appendLog(it) ?: return
    }
  }

  override fun dispose() {
    for (item in _map.values) {
      deleteFile(item.command)
    }
    _map.clear()
  }

  private fun deleteFile(command: D2Command.Generate) {
    command.targetFile.let { Files.deleteIfExists(it) }
  }

  private fun prepare(command: D2Command<*>, listener: ProcessListener?) =
    KillableColoredProcessHandler.Silent(command.createCommandLine().apply {
      withEnvironment(command.envVars())
    }).apply {
      if (listener != null) {
        addProcessListener(listener)
      }
    }

  // null if d2 executable not found
  private fun <O> simpleRun(cmd: D2Command<O>): O? {
    try {
      val processOut = ScriptRunnerUtil.getProcessOutput(
        cmd.createCommandLine(),
        ScriptRunnerUtil.STDOUT_OR_STDERR_OUTPUT_KEY_FILTER,
        500
      )
      return cmd.parseOutput(processOut)
    } catch (e: Exception) {
      LOG.error(e)
      return null
    }
  }
}