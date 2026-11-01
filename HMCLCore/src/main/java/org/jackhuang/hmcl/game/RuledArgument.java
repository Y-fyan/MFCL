/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.game;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;
import org.jackhuang.hmcl.util.Immutable;

import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

import static org.jackhuang.hmcl.util.gson.JsonUtils.listTypeOf;

/**
 *
 * @author huangyuhui
 */
@JsonAdapter(RuledArgument.Serializer.class)
@Immutable
public record RuledArgument(List<CompatibilityRule> rules, List<String> value) implements Argument {

    public RuledArgument() {
        this(null, null);
    }

    @Override
    public List<CompatibilityRule> rules() {
        return Collections.unmodifiableList(rules);
    }

    @Override
    public List<String> value() {
        return Collections.unmodifiableList(value);
    }

    @Override
    public Object clone() {
        return new RuledArgument(
                rules == null ? null : new ArrayList<>(rules),
                value == null ? null : new ArrayList<>(value)
        );
    }

    /// Returns a read-only view of the rule list, preserving an absent list.
    ///
    /// @return rules or `null`
    public @Nullable @UnmodifiableView List<@Nullable CompatibilityRule> rulesOrNull() {
        return rules == null ? null : Collections.unmodifiableList(rules);
    }

    /// Returns a read-only view of raw values, preserving an absent list.
    ///
    /// @return values or `null`
    public @Nullable @UnmodifiableView List<@Nullable String> valuesOrNull() {
        return value == null ? null : Collections.unmodifiableList(value);
    }

    /// Selects the raw argument values that apply to the current environment.
    ///
    /// Null values are omitted, matching [#toString(Map, Map)]. Placeholder expansion is left to
    /// the caller. A mutable result is returned so callers can assemble larger argument groups.
    ///
    /// @param features supported launch features
    /// @return selected raw argument values
    public List<String> selectRawValues(Map<String, Boolean> features) {
        List<String> result = new ArrayList<>();
        if (CompatibilityRule.appliesToCurrentEnvironment(rules, features) && value != null) {
            for (@Nullable String argument : value) if (argument != null) result.add(argument);
        }
        return result;
    }

    @Override
    public List<String> toString(Map<String, String> keys, Map<String, Boolean> features) {
        if (CompatibilityRule.appliesToCurrentEnvironment(rules, features) && value != null)
            return value.stream()
                    .filter(Objects::nonNull)
                    .map(StringArgument::new)
                    .map(str -> str.toString(keys, features).get(0))
                    .collect(Collectors.toList());
        return Collections.emptyList();
    }

    public static class Serializer implements JsonSerializer<RuledArgument>, JsonDeserializer<RuledArgument> {
        @Override
        public JsonElement serialize(RuledArgument src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.add("rules", context.serialize(src.rules));
            obj.add("value", context.serialize(src.value));
            return obj;
        }

        @Override
        public RuledArgument deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            List<CompatibilityRule> rules = context.deserialize(obj.get("rules"), listTypeOf(CompatibilityRule.class).getType());

            JsonElement valuesElement;
            if (obj.has("values")) {
                valuesElement = obj.get("values");
            } else if (obj.has("value")) {
                valuesElement = obj.get("value");
            } else {
                throw new JsonParseException("RuledArguments instance does not have either value or values member.");
            }

            List<String> values;
            if (valuesElement.isJsonPrimitive()) {
                values = Collections.singletonList(valuesElement.getAsString());
            } else {
                values = context.deserialize(valuesElement, listTypeOf(String.class).getType());
            }

            return new RuledArgument(rules, values);
        }

    }
}
