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

import com.google.gson.JsonArray;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

/**
 * Dumps the live AI tool schemas to training/data/tool_schemas.json so the fine-tuning data
 * generator always trains against the real, current tool surface. Runs as part of the normal
 * test suite; the export is a side effect of verifying the schemas build.
 */
public class ToolSchemaDumpTest {

    @Test
    public void schemasBuildAndExportForTraining() throws Exception {
        JsonArray schemas = BaritoneTools.toolSchemas();
        assertTrue("expected a rich tool surface, got " + schemas.size(), schemas.size() >= 50);
        Path out = Path.of("training", "data", "tool_schemas.json");
        if (Files.isDirectory(out.getParent())) {
            Files.writeString(out, schemas.toString(), StandardCharsets.UTF_8);
        }
    }
}
