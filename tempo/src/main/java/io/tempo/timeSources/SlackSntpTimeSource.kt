/*
 * Copyright 2017 Allan Yoshio Hasegawa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.tempo.timeSources

import io.tempo.TimeSource
import io.tempo.TimeSourceConfig
import io.tempo.TimeSyncInfo
import io.tempo.internal.data.AndroidSntpClient
import io.tempo.internal.domain.SntpClient.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * A [TimeSource] implementation using a more forgiving SNTP algorithm. It queries the [ntpPool]
 * five times concurrently, then, removes all failures and queries where the round trip took more
 * than [maxRoundTripMs]. If one or more queries succeeds, we take the one with the median round
 * trip time and return it.
 *
 * @param[config]
 * @param[ntpPool] The address of the NTP pool.
 * @param[maxRoundTripMs] The maximum allowed round trip time in milliseconds.
 * @param[timeoutMs] The maximum time allowed per each query, in milliseconds.
 */
public class SlackSntpTimeSource(
    override val config: TimeSourceConfig = TimeSourceConfig(
        id = "default-slack-sntp",
        priority = 10
    ),
    private val ntpPool: String = "time.google.com",
    private val maxRoundTripMs: Int = 1_000,
    private val timeoutMs: Int = 10_000
) : TimeSource {

    public class AllRequestsFailure(errorMsg: String, cause: Throwable?)
        : RuntimeException(errorMsg, cause)

    override suspend fun requestTime(): TimeSyncInfo =
        withContext(Dispatchers.IO) {
            val hostAddress = AndroidSntpClient.queryHostAddress(ntpPool)
            val rawResults = (1..5)
                .map { async { requestTimeToAddress(hostAddress) } }
                .awaitAll()

            val results = turnSlowRequestsIntoFailure(rawResults)
            val successes = results.mapNotNull { it as? Result.Success }

            if (successes.isNotEmpty()) {
                // If at least one succeeds, sort by 'round trip time' and get median.
                successes
                    .sortedBy { it.roundTripTimeMs }
                    .map {
                        TimeSyncInfo(
                            requestTime = it.ntpTimeMs,
                            requestUptime = it.uptimeReferenceMs,
                        )
                    }
                    .elementAt(successes.size / 2)
            } else {
                // If all fail, throw 'AllRequestsFailure' exception.
                val failures = results.mapNotNull { it as? Result.Failure }
                val msgs = failures.joinToString("; ", prefix = "[", postfix = "]") { it.errorMsg }
                val errorMsg = "All NTP requests failed: $msgs"
                val cause = failures.firstOrNull()?.error
                throw AllRequestsFailure(errorMsg, cause)
            }
        }

    private fun turnSlowRequestsIntoFailure(rawResults: List<Result>): List<Result> =
        rawResults.map {
            when (it) {
                is Result.Success -> when (it.roundTripTimeMs > maxRoundTripMs) {
                    true -> Result.Failure(
                        null,
                        "RoundTrip time exceeded allowed threshold:" +
                            " took ${it.roundTripTimeMs}, but max is $maxRoundTripMs"
                    )
                    else -> it
                }
                else -> it
            }
        }

    private suspend fun requestTimeToAddress(address: InetAddress): Result =
        withContext(Dispatchers.IO) {
            try {
                AndroidSntpClient.requestTime(address, AndroidSntpClient.NTP_PORT, timeoutMs)
            } catch (error: Throwable) {
                val msg = error.message ?: "Error requesting time source time."
                Result.Failure(error, msg)
            }
        }
}