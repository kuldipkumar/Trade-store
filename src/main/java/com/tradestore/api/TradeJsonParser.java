package com.tradestore.api;

import com.tradestore.domain.Channel;
import com.tradestore.domain.TradeSubmission;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns trade JSON into a {@link TradeSubmission}, strictly by JSON type (decisions D2/D3): the text fields must
 * be strings and {@code version} a whole number that fits an int, or absent/null. Nothing is coerced (1.9 is not
 * 1, "1" is not 1). Values are not judged here: blank IDs or bad dates are the validators' job, so they are
 * rejected and audited as INVALID_FIELD. Unknown fields are ignored (US-607.2). The original text is kept for
 * the audit trail.
 */
@Component
public class TradeJsonParser {

    private final JsonMapper json;

    public TradeJsonParser(JsonMapper json) {
        this.json = json;
    }

    public TradeSubmission parse(String body, Channel channel) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw new MalformedTradeException("The body is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedTradeException("The body must be a JSON object");
        }
        return new TradeSubmission(text(root, "tradeId"), version(root), text(root, "counterPartyId"),
                text(root, "bookId"), text(root, "maturityDate"), channel, body);
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new MalformedTradeException(field + " must be a JSON string");
        }
        return node.asString();
    }

    private static Integer version(JsonNode root) {
        JsonNode node = root.get("version");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new MalformedTradeException("version must be a whole number");
        }
        return node.intValue();
    }
}
