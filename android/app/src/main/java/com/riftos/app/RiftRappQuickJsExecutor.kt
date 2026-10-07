package com.riftos.app

import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking

/**
 * Generic zero-authority QuickJS execution engine for RAPP runtimes.
 *
 * Runtime code receives one JSON event envelope and returns one JSON output
 * envelope. It has no direct RiftFS, network, process, Git, Android, or signing
 * authority; all privileged work must return a HostEffect and pass through the
 * normal RiftRappCapabilityBroker.
 */
class RiftRappQuickJsExecutor {
    companion object {
        private const val MAX_RUNTIME_BYTES =
            1024 * 1024
        private const val MAX_INPUT_BYTES =
            512 * 1024
        private const val MAX_OUTPUT_BYTES =
            512 * 1024
        private const val EVALUATION_TIMEOUT_MS =
            6_000L

        private const val ENTRY_SOURCE =
            """
            (() => {
              const main = globalThis.riftRappMain;
              if (typeof main !== "function") {
                throw new Error("RAPP QuickJS runtime must define globalThis.riftRappMain(inputJson)");
              }
              const value = main(__rift_rapp_input());
              if (value && typeof value.then === "function") {
                throw new Error("RAPP QuickJS runtime must be synchronous");
              }
              const json = typeof value === "string" ? value : JSON.stringify(value);
              if (typeof json !== "string" || json.length === 0) {
                throw new Error("RAPP QuickJS runtime returned no JSON");
              }
              __rift_rapp_result(json);
            })();
            """
    }

    fun execute(
        runtime: ByteArray,
        input: ByteArray,
        outputCapacity: Int
    ): ByteArray {
        require(
            runtime.size in
                1..MAX_RUNTIME_BYTES
        ) {
            "RAPP QuickJS runtime is out of bounds"
        }
        require(
            input.size <=
                MAX_INPUT_BYTES
        ) {
            "RAPP QuickJS input is out of bounds"
        }
        require(
            outputCapacity in
                1..MAX_OUTPUT_BYTES
        ) {
            "RAPP QuickJS output capacity is out of bounds"
        }

        val runtimeSource =
            runtime.toString(
                Charsets.UTF_8
            )
        val inputJson =
            input.toString(
                Charsets.UTF_8
            )
        require(
            runtimeSource
                .toByteArray(
                    Charsets.UTF_8
                )
                .contentEquals(
                    runtime
                )
        ) {
            "RAPP QuickJS runtime is not canonical UTF-8"
        }
        require(
            inputJson
                .toByteArray(
                    Charsets.UTF_8
                )
                .contentEquals(
                    input
                )
        ) {
            "RAPP QuickJS input is not canonical UTF-8"
        }

        var resultJson:
            String? = null

        runBlocking {
            quickJs {
                evaluationTimeoutMillis =
                    EVALUATION_TIMEOUT_MS

                function(
                    "__rift_rapp_input"
                ) {
                    inputJson
                }
                function(
                    "__rift_rapp_result"
                ) {
                    values ->
                    require(
                        resultJson ==
                            null
                    ) {
                        "RAPP QuickJS runtime returned multiple results"
                    }
                    resultJson =
                        values
                            .firstOrNull()
                            ?.toString()
                            ?: error(
                                "RAPP QuickJS runtime returned null"
                            )
                    Unit
                }

                evaluate<Any?>(
                    runtimeSource,
                    filename =
                        "runtime.rapp.js"
                )
                evaluate<Any?>(
                    ENTRY_SOURCE,
                    filename =
                        "runtime.rapp.entry.js"
                )
            }
        }

        val output =
            resultJson
                ?.toByteArray(
                    Charsets.UTF_8
                )
                ?: error(
                    "RAPP QuickJS runtime returned no result"
                )

        require(
            output.isNotEmpty() &&
                output.size <=
                    minOf(
                        outputCapacity,
                        MAX_OUTPUT_BYTES
                    )
        ) {
            "RAPP QuickJS output is out of bounds"
        }

        return output
    }
}
