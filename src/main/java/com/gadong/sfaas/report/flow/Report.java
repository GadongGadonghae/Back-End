package com.gadong.sfaas.report.flow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 보고서 결재·전송 (v0.3.15, MES 명세 docs/api-spec-report-approval-mes.md).
 * 작성(WRITTEN) → 결재(APPROVED) → 전송(SENT). 전송된 보고서는 임시 MES에서 캠퍼스장이 봐요.
 * REPLAN(계획수정)은 내용이 reassignment_report에 있고 여기엔 그 번호(replan_report_id)와 만든 순간의 JSON을 둬요.
 * DAILY(일일 생산)는 화면이 계산한 내용을 그대로 저장해요 (서버 계산은 나중에).
 */
@Entity
@Table(name = "reports", uniqueConstraints = @UniqueConstraint(columnNames = {"kind", "target_key"}))
@Getter
@NoArgsConstructor
public class Report {

    /** 작성 중: AI 보고서를 만들었지만 아직 결재 요청 전 (11/20). 공장장에게 알리지 않아요 */
    public static final String DRAFT = "DRAFT";
    /** 작성 완료 · 결재 요청 */
    public static final String WRITTEN = "WRITTEN";
    public static final String APPROVED = "APPROVED";
    public static final String SENT = "SENT";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "report_id") private Long reportId;
    @Column(name = "kind", length = 10, nullable = false) private String kind;
    @Column(name = "target_key", length = 40, nullable = false) private String targetKey;
    @Column(name = "title", length = 100, nullable = false) private String title;
    @Column(name = "status", length = 10, nullable = false) private String status;
    @Column(name = "campus_id", length = 10, nullable = false) private String campusId;
    @Column(name = "factory_id", nullable = false) private Integer factoryId;
    @Column(name = "content", columnDefinition = "TEXT") private String content;
    @Column(name = "replan_report_id") private Long replanReportId;
    @Column(name = "written_by") private Integer writtenBy;
    @Column(name = "written_at") private LocalDateTime writtenAt;
    @Column(name = "approved_by") private Integer approvedBy;
    @Column(name = "approved_at") private LocalDateTime approvedAt;
    @Column(name = "sent_by") private Integer sentBy;
    @Column(name = "sent_at") private LocalDateTime sentAt;

    public Report(String kind, String targetKey, String campusId, int factoryId) {
        this.kind = kind;
        this.targetKey = targetKey;
        this.campusId = campusId;
        this.factoryId = factoryId;
        this.status = WRITTEN;
    }

    /** (다시) 작성: 내용을 바꾸고 결재를 처음부터 */
    public void write(String title, String content, Long replanReportId, Integer by, LocalDateTime at) {
        this.title = title;
        this.content = content;
        this.replanReportId = replanReportId;
        this.status = WRITTEN;
        this.writtenBy = by;
        this.writtenAt = at;
        this.approvedBy = null;
        this.approvedAt = null;
        this.sentBy = null;
        this.sentAt = null;
    }

    /** 작성 중으로 기록 (AI 보고서를 새로 만들면, 다시 만들어도 결재는 처음부터) */
    public void draft(String title, String content, Long replanReportId, Integer by, LocalDateTime at) {
        write(title, content, replanReportId, by, at);
        this.status = DRAFT;
    }

    /** 작성 완료 · 결재 요청 (작성 중 → 작성). content가 있으면 그 순간 문서로 바꿔요 */
    public void request(Integer by, LocalDateTime at, String content) {
        this.status = WRITTEN;
        this.writtenBy = by;
        this.writtenAt = at;
        if (content != null) this.content = content;
    }

    /** 회수: 결재 요청을 거둬 작성 중으로 (결재 전에만) */
    public void recall() {
        this.status = DRAFT;
    }

    public void approve(Integer by, LocalDateTime at, String content) {
        this.status = APPROVED;
        this.approvedBy = by;
        this.approvedAt = at;
        if (content != null) this.content = content;
    }

    public void send(Integer by, LocalDateTime at) {
        this.status = SENT;
        this.sentBy = by;
        this.sentAt = at;
    }
}
