package com.agentoffice.mapper.approval;

import com.agentoffice.dto.approval.ApprovalDto;
import com.agentoffice.dto.approval.PolicyDto;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.approval.ApprovalPolicy;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper
public interface ApprovalMapper {
    ApprovalDto toDto(Approval a);

    List<ApprovalDto> toDtos(List<Approval> approvals);

    PolicyDto toDto(ApprovalPolicy p);

    List<PolicyDto> toPolicyDtos(List<ApprovalPolicy> policies);
}
