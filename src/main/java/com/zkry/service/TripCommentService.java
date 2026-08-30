package com.zkry.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zkry.common.exception.BizException;
import com.zkry.domain.dto.collaboration.CreateTripCommentRequest;
import com.zkry.domain.dto.collaboration.UpdateCommentStatusRequest;
import com.zkry.domain.entity.TripComment;
import com.zkry.domain.vo.TripCommentView;
import com.zkry.mapper.TripCommentMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripCommentService {

    private static final List<String> TARGET_TYPES = List.of("PLAN", "DAY", "POI", "ROUTE");
    private static final List<String> STATUSES = List.of("OPEN", "RESOLVED");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final TripCommentMapper mapper;
    private final SystemUserService userService;

    public TripCommentService(TripCommentMapper mapper, SystemUserService userService) {
        this.mapper = mapper;
        this.userService = userService;
    }

    public List<TripCommentView> list(String planId, String targetType, String targetRef) {
        var query = Wrappers.<TripComment>lambdaQuery()
            .eq(TripComment::getPlanId, requiredPlanId(planId))
            .orderByAsc(TripComment::getCreateTime);
        if (targetType != null && !targetType.isBlank()) {
            query.eq(TripComment::getTargetType, normalize(targetType, TARGET_TYPES, "评论目标类型"));
        }
        if (targetRef != null && !targetRef.isBlank()) {
            query.eq(TripComment::getTargetRef, targetRef.trim());
        }
        return mapper.selectList(query).stream().map(this::toView).toList();
    }

    @Transactional
    public TripCommentView create(String planId, Long authenticatedUserId, CreateTripCommentRequest request) {
        if (authenticatedUserId == null) throw new BizException("请先登录后再评论。");
        if (request == null) throw new BizException("评论请求不能为空。");
        String content = request.content() == null ? "" : request.content().trim();
        if (content.isBlank()) throw new BizException("评论内容不能为空。");
        if (content.length() > 1000) throw new BizException("评论内容不能超过 1000 个字符。");
        userService.getEntity(authenticatedUserId);
        if (request.parent_id() != null && mapper.selectById(request.parent_id()) == null) {
            throw new BizException("回复的评论不存在。");
        }

        TripComment comment = new TripComment();
        comment.setPlanId(requiredPlanId(planId));
        comment.setUserId(authenticatedUserId);
        comment.setParentId(request.parent_id());
        comment.setTargetType(normalizeDefault(request.target_type(), TARGET_TYPES, "PLAN", "评论目标类型"));
        comment.setTargetRef(request.target_ref() == null ? "" : request.target_ref().trim());
        comment.setContent(content);
        comment.setStatus("OPEN");
        comment.setLikeCount(0);
        mapper.insert(comment);
        return toView(comment);
    }

    @Transactional
    public TripCommentView updateStatus(String planId, Long id, UpdateCommentStatusRequest request) {
        TripComment comment = getEntity(planId, id);
        String status = request == null
            ? ""
            : normalize(request.status(), STATUSES, "评论状态");
        comment.setStatus(status);
        mapper.updateById(comment);
        return toView(comment);
    }

    @Transactional
    public TripCommentView like(String planId, Long id) {
        TripComment comment = getEntity(planId, id);
        comment.setLikeCount((comment.getLikeCount() == null ? 0 : comment.getLikeCount()) + 1);
        mapper.updateById(comment);
        return toView(comment);
    }

    @Transactional
    public void delete(String planId, Long id) {
        TripComment comment = getEntity(planId, id);
        mapper.deleteById(id);
    }

    private TripComment getEntity(Long id) {
        TripComment comment = id == null ? null : mapper.selectById(id);
        if (comment == null) throw new BizException("评论不存在，id=" + id);
        return comment;
    }

    private TripComment getEntity(String planId, Long id) {
        TripComment comment = getEntity(id);
        if (!requiredPlanId(planId).equals(comment.getPlanId())) {
            throw new BizException("评论不属于当前行程。");
        }
        return comment;
    }

    private TripCommentView toView(TripComment comment) {
        return new TripCommentView(
            comment.getId(), comment.getPlanId(), comment.getParentId(), comment.getTargetType(),
            comment.getTargetRef(), comment.getContent(), comment.getStatus(), comment.getLikeCount(),
            userService.get(comment.getUserId()), format(comment.getCreateTime()), format(comment.getUpdateTime())
        );
    }

    private String normalize(String value, List<String> allowed, String label) {
        if (value == null || value.isBlank()) throw new BizException(label + "不能为空。");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BizException(label + "无效：" + value);
        return normalized;
    }

    private String normalizeDefault(String value, List<String> allowed, String fallback, String label) {
        return value == null || value.isBlank() ? fallback : normalize(value, allowed, label);
    }

    private String requiredPlanId(String value) {
        if (value == null || value.isBlank()) throw new BizException("行程 ID 不能为空。");
        return value.trim();
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : TIME.format(value);
    }
}
