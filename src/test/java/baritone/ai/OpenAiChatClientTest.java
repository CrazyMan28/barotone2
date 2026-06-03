/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.ai;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiChatClientTest {

    @Test
    public void retriesOnRateLimitAndServerErrors() {
        assertTrue(OpenAiChatClient.shouldRetry(429));
        assertTrue(OpenAiChatClient.shouldRetry(500));
        assertTrue(OpenAiChatClient.shouldRetry(502));
        assertTrue(OpenAiChatClient.shouldRetry(503));
    }

    @Test
    public void doesNotRetryOnClientErrorsOrSuccess() {
        assertFalse(OpenAiChatClient.shouldRetry(200));
        assertFalse(OpenAiChatClient.shouldRetry(400));
        assertFalse(OpenAiChatClient.shouldRetry(401));
        assertFalse(OpenAiChatClient.shouldRetry(403));
        assertFalse(OpenAiChatClient.shouldRetry(404));
    }

    @Test
    public void backoffGrowsExponentially() {
        assertEquals(1000L, OpenAiChatClient.backoffMillis(0, 1000L));
        assertEquals(2000L, OpenAiChatClient.backoffMillis(1, 1000L));
        assertEquals(4000L, OpenAiChatClient.backoffMillis(2, 1000L));
        assertEquals(8000L, OpenAiChatClient.backoffMillis(3, 1000L));
    }

    @Test
    public void backoffIsCappedAndHandlesZeroBase() {
        assertEquals(30_000L, OpenAiChatClient.backoffMillis(40, 1000L));
        assertTrue(OpenAiChatClient.backoffMillis(8, 1000L) <= 30_000L);
        assertEquals(0L, OpenAiChatClient.backoffMillis(0, 0L));
    }
}
