package com.agentoffice.mapper.usage;

import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.entity.usage.BudgetChange;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface BudgetMapper {
    BudgetDto.View toView(Budget b);

    List<BudgetDto.View> toViews(List<Budget> budgets);

    @Mapping(target = "oldLimitMicroUsd", source = "oldLimit")
    @Mapping(target = "newLimitMicroUsd", source = "newLimit")
    BudgetDto.Change toChange(BudgetChange c);

    List<BudgetDto.Change> toChanges(List<BudgetChange> changes);
}
