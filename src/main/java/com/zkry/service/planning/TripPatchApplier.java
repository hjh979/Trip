package com.zkry.service.planning;

import com.zkry.common.exception.BizException;
import com.zkry.common.util.JsonUtils;
import com.zkry.domain.dto.TripPatch;
import com.zkry.domain.dto.TripPatchOperation;
import com.zkry.domain.dto.TripPlan;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/** Applies a bounded RFC 6902 subset and converts the result back to TripPlan. */
@Service
public class TripPatchApplier {

    public TripPlan apply(TripPlan plan, TripPatch patch) {
        if (plan == null || patch == null || patch.operations().isEmpty()) return plan;
        JsonNode root = JsonUtils.getObjectMapper().valueToTree(plan);
        for (TripPatchOperation operation : patch.operations()) apply(root, operation);
        return JsonUtils.getObjectMapper().convertValue(root, TripPlan.class);
    }

    private void apply(JsonNode root, TripPatchOperation operation) {
        if (operation == null || operation.op() == null || operation.path() == null) {
            throw new BizException("行程 Patch 操作不完整");
        }
        String op = operation.op().trim().toLowerCase();
        if ("move".equals(op)) {
            if (operation.from() == null) throw new BizException("move 操作缺少 from");
            JsonNode moved = read(root, operation.from());
            remove(root, operation.from());
            set(root, operation.path(), moved, true);
            return;
        }
        if ("remove".equals(op)) {
            remove(root, operation.path());
        } else if ("add".equals(op)) {
            set(root, operation.path(), JsonUtils.getObjectMapper().valueToTree(operation.value()), true);
        } else if ("replace".equals(op)) {
            set(root, operation.path(), JsonUtils.getObjectMapper().valueToTree(operation.value()), false);
        } else {
            throw new BizException("不支持的行程 Patch 操作: " + operation.op());
        }
    }

    private JsonNode read(JsonNode root, String path) {
        JsonNode node = root;
        for (String token : tokens(path)) {
            node = node == null ? null : node.get(token);
        }
        if (node == null) throw new BizException("Patch 路径不存在: " + path);
        return node.deepCopy();
    }

    private void remove(JsonNode root, String path) {
        Parent parent = parent(root, path);
        if (parent.container() instanceof ObjectNode object) {
            if (!object.has(parent.token())) throw new BizException("Patch 路径不存在: " + path);
            object.remove(parent.token());
        } else if (parent.container() instanceof ArrayNode array) {
            int index = index(parent.token(), array.size(), false);
            array.remove(index);
        } else throw new BizException("Patch 父路径不是容器: " + path);
    }

    private void set(JsonNode root, String path, JsonNode value, boolean allowAppend) {
        Parent parent = parent(root, path);
        if (parent.container() instanceof ObjectNode object) {
            if (!allowAppend && !object.has(parent.token())) {
                throw new BizException("Patch 路径不存在: " + path);
            }
            object.set(parent.token(), value);
        } else if (parent.container() instanceof ArrayNode array) {
            int index = index(parent.token(), array.size(), allowAppend);
            if (allowAppend && index == array.size()) array.add(value); else array.set(index, value);
        } else throw new BizException("Patch 父路径不是容器: " + path);
    }

    private Parent parent(JsonNode root, String path) {
        List<String> tokens = tokens(path);
        if (tokens.isEmpty()) throw new BizException("Patch path 不能为空");
        JsonNode current = root;
        for (int i = 0; i < tokens.size() - 1; i++) {
            current = current.get(tokens.get(i));
            if (current == null) throw new BizException("Patch 父路径不存在: " + path);
        }
        return new Parent(current, tokens.getLast());
    }

    private int index(String token, int size, boolean allowAppend) {
        if (allowAppend && "-".equals(token)) return size;
        try {
            int index = Integer.parseInt(token);
            if (index < 0 || index >= size || (allowAppend && index > size)) throw new NumberFormatException();
            return index;
        } catch (NumberFormatException ex) {
            throw new BizException("Patch 数组下标非法: " + token);
        }
    }

    private List<String> tokens(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) throw new BizException("Patch path 必须以 / 开头");
        List<String> values = new ArrayList<>();
        for (String token : path.substring(1).split("/", -1)) {
            values.add(token.replace("~1", "/").replace("~0", "~"));
        }
        return values;
    }

    private record Parent(JsonNode container, String token) { }
}
