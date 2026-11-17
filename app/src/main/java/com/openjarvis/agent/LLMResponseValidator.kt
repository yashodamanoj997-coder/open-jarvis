package com.openjarvis.agent

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object LLMResponseValidator {

    private val knownActions = setOf(
        "open_app", "tap", "tap_coords", "long_press", "type",
        "clear_type", "swipe", "scroll", "press_back", "press_home",
        "press_recents", "wait_for", "screenshot", "read_screen",
        "ai_prompt", "mcp_call", "reply_notification",
        "read_clipboard", "write_clipboard", "highlight_element", "error"
    )

    fun validate(rawResponse: String): ValidationResult {
        val errors = mutableListOf<String>()

        val clean = rawResponse.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        if (!clean.startsWith("[") && !clean.startsWith("{")) {
            return ValidationResult(
                false, emptyList(), listOf("Response is not JSON"), false
            )
        }

        val parsed = try {
            if (clean.startsWith("[")) {
                JSONArray(clean)
            } else {
                val root = JSONObject(clean)

                if (root.has("actions")) {
                    val reply = root.optString("reply", "").trim()
                    val actionArray = root.optJSONArray("actions") ?: JSONArray()

                    return validateActions(actionArray, reply, errors)
                }

                JSONArray().put(root)
            }
        } catch (e: JSONException) {
            return ValidationResult(
                false, emptyList(),
                listOf("Invalid JSON: ${e.message}"), false
            )
        }

        return validateActions(parsed, null, errors)
    }

    private fun validateActions(
        parsed: JSONArray,
        reply: String?,
        errors: MutableList<String>
    ): ValidationResult {
        val actions = mutableListOf<Action>()

        for (i in 0 until parsed.length()) {
            val obj = try {
                parsed.getJSONObject(i)
            } catch (e: Exception) {
                errors.add("Item $i is not an object")
                continue
            }

            val actionType = obj.optString("action", "")

            if (actionType.isBlank()) {
                errors.add("Item $i missing 'action' field")
                continue
            }

            if (actionType !in knownActions) {
                errors.add("Unknown action type: $actionType — skipping")
                continue
            }

            actions.add(parseAction(obj))
        }

        if (actions.size > 50) {
            errors.add("Action plan capped at 50 steps")
        }

        val finalActions = actions.take(50)
        val hasReply = !reply.isNullOrBlank()

        return ValidationResult(
            isValid = finalActions.isNotEmpty() || hasReply,
            actions = finalActions,
            errors = errors,
            wasRepaired = errors.isNotEmpty() && (finalActions.isNotEmpty() || hasReply),
            reply = reply
        )
    }

    private fun parseAction(obj: JSONObject): Action {
        return Action(
            action = obj.getString("action"),
            packageName = obj.optString("package", null).takeIf { it.isNotBlank() },
            label = obj.optString("label", null).takeIf { it.isNotBlank() },
            text = obj.optString("text", null).takeIf { it.isNotBlank() },
            value = obj.optString("value", null).takeIf { it.isNotBlank() },
            hint = obj.optString("hint", null).takeIf { it.isNotBlank() },
            direction = obj.optString("direction", null).takeIf { it.isNotBlank() },
            x = if (obj.has("x")) obj.getInt("x") else null,
            y = if (obj.has("y")) obj.getInt("y") else null,
            distance = obj.optString("distance", null).takeIf { it.isNotBlank() },
            timeoutMs = obj.optLong("timeout_ms", 3000L),
            message = obj.optString("message", null).takeIf { it.isNotBlank() },
            prompt = obj.optString("prompt", null).takeIf { it.isNotBlank() },
            outputKey = obj.optString("outputKey", null).takeIf { it.isNotBlank() }
        )
    }

    data class ValidationResult(
        val isValid: Boolean,
        val actions: List<Action>,
        val errors: List<String>,
        val wasRepaired: Boolean,
        val reply: String? = null
    )
}
