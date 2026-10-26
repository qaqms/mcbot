local call, attempt = mc.call, mc.try_call

local function target_or_goal(target, revision)
    if target then return target,revision end
    local goal=mc.goal()
    assert(goal and goal.type=="building" and goal.target and goal.expected_revision)
    if revision then assert(revision==goal.expected_revision) end
    return goal.target,goal.expected_revision
end

local function move(cell, item, bounds, walk_only, candidates, revision)
    local p = {x=cell.x,y=cell.y,z=cell.z}
    local args = {x=p.x,y=p.y,z=p.z,work_target=p,exact=true,
        arrival="work",bounds=bounds,walk_only=walk_only,
        clear_obstructions=not item and walk_only~=true or nil}
    if item then args.place_item=item; args.face=cell.face; args.temporary_purpose=cell.purpose end
    if candidates and #candidates>1 then
        args.work_target,args.place_item,args.face=nil,nil,nil
        args.work_targets,args.expected_revision={},revision
        for _,candidate in ipairs(candidates) do
            local c=candidate.cell
            args.work_targets[#args.work_targets+1]={x=c.x,y=c.y,z=c.z,item=c.item,face=c.face}
        end
    end
    local result = attempt("move_to", args)
    if not result.ok then
        if result.failure_kind=="TARGET_LOST" then error("unknown work facts") end
        call("status",{details=true})
    end
    return result.ok, result, result.data.departure_blocked
end

local function checked(target, revision, all, cursor)
    local pages, offset, stamp = {}, 0, nil
    while true do
        local d=call("build_diff",{target=target,expected_revision=revision,offset=offset,
            work_scan=not all,work_cursor=not all and (cursor or 0) or nil}).data
        revision=revision or d.target_revision
        if d.target_revision~=revision or not d.observation_complete or not d.spatial_stable
            or d.budget_exhausted then
            return nil,"incomplete_or_unstable_observation"
        end
        if stamp and stamp~=d.observation_key then return nil,"world_or_material_changed_between_pages" end
        stamp=d.observation_key
        if d.work_scan or all and d.total and d.total>32 then return {d} end
        if d.goal_satisfied and not all then return {d} end
        pages[#pages+1]=d
        if d.next_offset==-1 then return pages end
        offset=d.next_offset
    end
end

-- Pin the first revision for every continuation; incomplete material support is a gap, not a lost page.
function mc.build_diff(target, revision, offset)
    target,revision=target_or_goal(target,revision)
    if offset then return call("build_diff",{target=target,expected_revision=revision,offset=offset}).data end
    local pages,reason=checked(target,revision,true)
    if not pages then return {status="blocked",blocked=reason,goal_satisfied=false} end
    local d=pages[1]
    if d.total and d.total>32 then return d end
    local cells,counts={},{}
    for _,page in ipairs(pages) do
        for _,cell in ipairs(page.cells) do
            cells[#cells+1]=cell
            counts[cell.state]=(counts[cell.state] or 0)+1
        end
    end
    local material_offset=d.material_next_offset
    while material_offset and material_offset~=-1 do
        local nextpage=call("build_diff",{target=target,expected_revision=d.target_revision,
            offset=0,material_offset=material_offset}).data
        if not nextpage.observation_complete or not nextpage.spatial_stable or nextpage.budget_exhausted
            or nextpage.target_revision~=d.target_revision or nextpage.observation_key~=d.observation_key then
            return {status="blocked",blocked="world_or_material_changed_between_pages",goal_satisfied=false}
        end
        for _,item in ipairs(nextpage.materials) do d.materials[#d.materials+1]=item end
        material_offset=nextpage.material_next_offset
    end
    d.cells,d.counts=cells,counts
    d.details_complete,d.next_offset,d.material_next_offset=true,-1,-1
    d.page_counts,d.page_remaining,d.page_satisfied=nil,nil,nil
    d.details_scope="whole_target"
    d.work_assessment_complete=not counts.not_assessed and not counts.unknown
    return d
end

local function workkey(cell, kind, stamp)
    return kind..":"..cell.x..":"..cell.y..":"..cell.z..":"..stamp
end

local function lower(candidate, current)
    return not current or candidate.cell.y<current.cell.y
end

local function relocate(site, revision, stamp, seen)
    local plan=site.relocation
    if not plan then return false end
    local key=plan.operation..":"..plan.x..":"..plan.y..":"..plan.z..":"..(stamp or "")
    if seen[key] then return false end
    seen[key]=true
    local result
    if plan.operation=="move" then
        result=attempt("move_to",{x=plan.x,y=plan.y,z=plan.z,exact=true,
            walk_only=plan.walk_only,bounds=site.bounds})
    elseif plan.operation=="lower" then
        result=attempt("break_block",{x=plan.x,y=plan.y,z=plan.z,expected_block=plan.expected_block,
            site_revision=revision,temporary_cleanup=plan.temporary_cleanup})
        if result.ok then
            local settled=attempt("move_to",{x=plan.x,y=plan.y,z=plan.z,exact=true,
                walk_only=true,bounds=site.bounds})
            if not settled.ok then
                settled.data.removed=result.data.removed
                result=settled
            end
        end
    else return false end
    if not result.ok then call("status",{details=true}) end
    return result.ok,result
end

function mc.build(target, options)
    local revision
    target,revision=target_or_goal(target)
    local o=options or {}
    local rounds, actions=o.max_rounds or 0,o.max_actions or 12
    assert(type(rounds)=="number" and rounds>=0 and rounds<=2147483647 and rounds%1==0)
    assert(type(actions)=="number" and actions>=1 and actions<=12 and actions%1==0)
    assert(o.access_route==nil or type(o.access_route)=="table")
    for key in pairs(o) do
        assert(key=="access_route" or key=="bounds" or key=="max_rounds"
            or key=="max_actions" or key=="temporary")
    end
    local site=call("build_site",{operation="begin",target=target,
        access_route=o.access_route,bounds=o.bounds,expected_revision=revision}).data
    revision=revision or site.target_revision
    assert(site.target_revision==revision)
    local temporary_possible=site.temporary and #site.temporary>0 or false
    local pending_repairs=(site.repairs_pending or 0)>0
    local function remember(result)
        if result and (result.data.placed_blocks or 0)>0 then temporary_possible=true end
        if result and (result.data.removed or (result.data.removed_blocks or 0)>0) then pending_repairs=true end
    end
    local placed,cleared,consumed,repaired=0,0,0,0
    local effects,omitted={},0
    local last_pages,initial_remaining=nil,nil
    local relocations,recovery_seen,cleanup=0,{},nil
    local cleanup_stamp=nil
    local cleanup_final=false
    local function cleanup_after_stall(final)
        if final then cleanup_final=true end
        if not temporary_possible then return end
        local current=call("build_site",{operation="query"}).data
        if current.temporary and #current.temporary>0 then
            local previous=cleanup and cleanup.removed or 0
            cleanup=mc.cleanup_temporary(revision)
            cleanup.removed=cleanup.removed+previous
        end
    end
    local function effect(cell,kind)
        if #effects<16 then
            effects[#effects+1]={x=cell.x,y=cell.y,z=cell.z,block=cell.block,kind=kind}
        else omitted=omitted+1 end
    end
    local function record_batch(result)
        placed=placed+(result.data.placed_blocks or 0)
        consumed=consumed+(result.data.consumed_count or 0)
        if result.data.placement_results then
            for _,cell in ipairs(result.data.placement_results) do
                if cell.ok then effect(cell,"placed") end
            end
        end
    end
    local function report(reason,round,pages,failure)
        if reason and not cleanup_final then cleanup_after_stall(true) end
        local current=pages~=nil
        if cleanup and cleanup.removed>0 then
            pages=checked(target,revision)
            current=pages~=nil
        end
        pages=pages or last_pages
        local first=pages and pages[1]
        local satisfied=current and first and first.goal_satisfied==true or false
        local result={ok=satisfied,status=satisfied and "completed" or "blocked",
            goal_satisfied=satisfied,blocked=not satisfied and reason or nil,
            target_revision=revision,rounds=round or 0,placed_blocks=placed,
            cleared_blocks=cleared,consumed_count=consumed,changed=placed+cleared>0,
            initial_remaining=initial_remaining,total=first and first.total,remaining=first and first.remaining,
            remaining_known=current,outer_goal_verified=false,
            effects=effects,effects_omitted=omitted,last_failure=failure}
        result.relocations=relocations
        result.nearby_repaired_blocks=repaired
        if reason=="construction_state_repeated" then
            result.model_reassessment_required=true
            result.blocked_detail="施工换位后再次遇到相同图纸实态；位移、库存变化和拆补次数不能证明进展。保留原图与实际效果，核对缺格并调整施工通路或顺序。"
        end
        if cleanup then
            result.temporary_removed=cleanup.removed
            result.temporary_retained=cleanup.retained
            result.temporary_cleanup_blocked=cleanup.blocked
            result.temporary_cleanup_failures=cleanup.failures
            result.temporary_cleanup_failures_omitted=cleanup.failures_omitted
            result.temporary_retained_reasons=cleanup.retained_reasons
            result.temporary_retained_reasons_omitted=cleanup.retained_reasons_omitted
        end
        if satisfied then result.evidence="fresh_whole_target_diff" end
        if pages then
            local counts,unresolved={},{}
            for _,page in ipairs(pages) do
                for _,cell in ipairs(page.cells) do
                    counts[cell.state]=(counts[cell.state] or 0)+1
                    if cell.state~="satisfied" and #unresolved<8 then
                        unresolved[#unresolved+1]={x=cell.x,y=cell.y,z=cell.z,state=cell.state,
                            actual_block=cell.actual_block,block=cell.block,
                            unsupported_reason=cell.unsupported_reason,clear_reason=cell.clear_reason,
                            placement_failure=cell.placement_failure}
                    end
                end
            end
            result.counts,result.unresolved=(satisfied or first.work_scan) and first.counts or counts,unresolved
            result.unresolved_omitted=first.remaining and first.remaining-#unresolved
            result.material_bill_complete=first.material_bill_complete
            result.design_components=first.design_components
            result.design_review=first.design_review
            result.clearing_work=first.clearing_work
        end
        print("mc.build",result)
        return result
    end
    local function completed(round,pages)
        cleanup_final=true
        if not temporary_possible then return report(nil,round,pages) end
        local current=call("build_site",{operation="query"}).data
        if current.temporary and #current.temporary>0 then
            local previous=cleanup and cleanup.removed or 0
            cleanup=mc.cleanup_temporary(revision)
            cleanup.removed=cleanup.removed+previous
            local fresh=call("build_site",{operation="query"}).data
            local retained=0
            for _,record in ipairs(fresh.temporary) do
                if not record.present_known or record.present~=false then retained=retained+1 end
            end
            cleanup.retained=retained
        end
        local final,why=checked(target,revision)
        if final and not final[1].goal_satisfied then return nil,final end
        return report(why or "cleanup_changed_target",round,final)
    end
    local temporary=o.temporary or {}
    assert(type(temporary)=="table" and #temporary<=16)
    if #temporary>0 then
        local pages,reason=checked(target,revision)
        if not pages then return report(reason) end
        if pages[1].goal_satisfied then
            local done=completed(0,pages)
            if done then return done end
        end
    end
    for _,cell in ipairs(temporary) do
        assert(cell.purpose=="support" or cell.purpose=="workstation")
        local existing=nil
        for _,record in ipairs(site.temporary) do
            if record.x==cell.x and record.y==cell.y and record.z==cell.z then existing=record end
        end
        if existing then
            if not existing.provenance_intact then return report("temporary_provenance_conflict") end
        else
            if not move(cell,cell.item,site.bounds) then return report("temporary_stance_unavailable") end
            call("place_block",{x=cell.x,y=cell.y,z=cell.z,item=cell.item,
                face=cell.face or "up",temporary_purpose=cell.purpose})
            temporary_possible=true
            site=call("build_site",{operation="query"}).data
        end
    end
    local round, failed, last_reason, last_failure=0,{},nil,nil
    local cursor,stamp=0,nil
    local walked_states={}
    local repairing=false
    local work_arrival=false
    local repair_stamp=nil
    local function recover(workstamp, required)
        site=call("build_site",{operation="query"}).data
        if required and not site.body_obstructs_target then return false end
        local ok,result=relocate(site,revision,workstamp,recovery_seen)
        remember(result)
        if ok then relocations=relocations+1; work_arrival=false end
        return ok
    end
    while rounds==0 or round<rounds do
        round=round+1
        call("status",{details=true})
        local pages,reason=checked(target,revision,nil,cursor)
        if not pages then return report(reason,round) end
        local workstamp=pages[1].progress_observation_key or pages[1].work_observation_key or pages[1].observation_key
        if stamp~=workstamp then
            failed={}
            if cursor~=0 then
                cursor=0
                pages,reason=checked(target,revision)
                if not pages then return report(reason,round) end
            end
        end
        stamp=workstamp
        last_pages=pages
        initial_remaining=initial_remaining or pages[1].remaining
        if pages[1].goal_satisfied then
            local done,remaining=completed(round-1,pages)
            if done then return done end
            pages,last_pages=remaining,remaining
            stamp=pages[1].progress_observation_key or pages[1].work_observation_key or pages[1].observation_key
            failed,cursor,repairing={},0,true
            walked_states={}
        end
        local moved=false
        local ready,clear,far,clears,fars=nil,nil,nil,{},{}
        local function scan(scanned)
            for _,page in ipairs(scanned) do
                for _,cell in ipairs(page.cells) do
                    if cell.repair_pending and cell.state~="satisfied" then pending_repairs=true end
                    local kind=nil
                    if cell.state=="ready" then kind="ready"
                    elseif cell.state=="needs_clear" and cell.clear_allowed then kind="clear"
                    elseif (cell.state=="out_of_reach" or cell.state=="blocked"
                        or cell.state=="state_conflict" and cell.actual_block and cell.block
                        and cell.actual_block~=cell.block) and cell.item
                        and cell.material_available then kind="place" end
                    if kind and (repairing or not cell.repair_pending) then
                        local key=workkey(cell,kind,stamp)
                        local candidate={cell=cell,offset=cell.source_offset or page.offset,key=key}
                        if not failed[key] then
                            if kind=="ready" and lower(candidate,ready) then ready=candidate end
                            if kind=="clear" then
                                clears[#clears+1]=candidate
                                if lower(candidate,clear) then clear=candidate end
                            end
                            if kind=="place" then
                                if lower(candidate,far) then far=candidate; fars={} end
                                if cell.y==far.cell.y and #fars<16 then fars[#fars+1]=candidate end
                            end
                        end
                    end
                end
            end
        end
        scan(pages)
        -- Keep only a bounded layer of intents, including members on later detail pages.
        local look=pages[1]
        local look_cursor=cursor
        while far and #fars<16 and (not ready or ready.cell.y>far.cell.y)
            and (not clear or clear.cell.y>far.cell.y)
            and look.work_next_cursor and look.work_next_cursor>=0 do
            local last=look.cells[#look.cells]
            if last and last.y and last.y>far.cell.y then break end
            if look.work_next_cursor<=look_cursor then return report("invalid_work_cursor",round,pages) end
            look_cursor=look.work_next_cursor
            local more,why=checked(target,revision,nil,look_cursor)
            if not more then return report(why,round) end
            if more[1].observation_key~=pages[1].observation_key then
                return report("world_or_material_changed_between_pages",round)
            end
            look=more[1]
            scan(more)
        end
        -- A usable work stance wins over future drawing occupancy. Walking alone does not reopen a pass.
        local obstructing=pages[1].body_obstructs_target
        if obstructing==nil then obstructing=site.body_obstructs_target end
        if not ready and not clear and obstructing then moved=recover(stamp,true) end
        if not ready and not clear then work_arrival=false end
        if not moved and (ready or clear or far or pending_repairs) then
            local checkpoint=call("build_site",{operation="checkpoint",expected_revision=revision}).data
            pending_repairs=(checkpoint.repairs_pending or 0)>0
            local local_stamp=pages[1].work_observation_key or stamp
            -- Complete the arrival's work before closing its opening. Then repair in place,
            -- across all drawing pages, before another route can leave the holes behind.
            if not work_arrival and pending_repairs
                and repair_stamp~=local_stamp then
                repair_stamp=local_stamp
                local result=call("build_nearby",{target=target,expected_revision=revision,
                    max_actions=actions,repairs_only=true})
                record_batch(result)
                local count=result.data.placed_blocks or 0
                repaired=repaired+count
                moved=count>0
            end
            if not moved and checkpoint.temporary and #checkpoint.temporary>=16 and cleanup_stamp~=stamp then
                cleanup_stamp=stamp
                temporary_possible=true
                local before=cleanup and cleanup.removed or 0
                cleanup_after_stall()
                if cleanup and cleanup.removed>before then moved=true end
            end
        end
        if moved then
            cursor=0
        elseif clear and (not ready or clear.cell.y<=ready.cell.y) and (not far or clear.cell.y<=far.cell.y) then
            local worked=0
            for _,candidate in ipairs(clears) do
                if worked>=actions then break end
                if candidate.cell.y==clear.cell.y then
                    worked=worked+1
                    local cell=candidate.cell
                    local ok,result,trapped=move(cell,nil,site.bounds)
                    remember(result)
                    if trapped then
                        if recover(stamp) then break end
                        return report("departure_blocked",round,pages,result.feedback)
                    end
                    if not ok then
                        failed[candidate.key]=true
                        last_reason,last_failure="clearing_stance_unavailable",result.feedback
                    else
                        failed[candidate.key]=true
                        local fresh=call("build_diff",{target=target,expected_revision=revision,offset=candidate.offset}).data
                        if not fresh.observation_complete or not fresh.spatial_stable or fresh.budget_exhausted then
                            return report("clear_observation_incomplete",round)
                        end
                        for _,c in ipairs(fresh.cells) do
                            if c.x==cell.x and c.y==cell.y and c.z==cell.z and c.state=="needs_clear"
                                and c.clear_allowed and c.actual_block==cell.actual_block then
                                local dug=call("break_block",{x=c.x,y=c.y,z=c.z,
                                    expected_block=c.actual_block,site_revision=revision,clear_obstructions=true}).data
                                cleared=cleared+(dug.cleared_blocks or 0)
                                if dug.removed then cleared=cleared+1; effect(c,"cleared")
                                else failed[candidate.key]=true end
                            end
                        end
                    end
                end
            end
        elseif ready and (not far or ready.cell.y<=far.cell.y) then
            local result=attempt("build_nearby",{target=target,expected_revision=revision,
                max_actions=actions,include_repairs=repairing})
            for _,page in ipairs(pages) do
                if page.work_scan or page.offset==ready.offset then
                    for _,cell in ipairs(page.cells) do
                        if cell.state=="ready" then failed[workkey(cell,"ready",stamp)]=true end
                    end
                end
            end
            record_batch(result)
            work_arrival=false
            if not result.ok then
                call("status",{details=true})
                failed[ready.key]=true
                last_reason,last_failure="nearby_work_unavailable",result.feedback
            elseif (result.data.placed_blocks or 0)==0 then
                failed[ready.key]=true
                last_reason="nearby_no_actual_progress"
            end
        elseif far then
            -- A route can reopen finished cells and change stock without advancing the drawing.
            -- Keep construction-state history across those changes, independently of failed stances.
            local drawing=pages[1].goal_observation_key
            if drawing and walked_states[drawing] then
                return report("construction_state_repeated",round,pages,last_failure)
            end
            local ok,result,trapped=move(far.cell,far.cell.item,site.bounds,nil,fars,revision)
            remember(result)
            if trapped then
                if not recover(stamp) then return report("departure_blocked",round,pages,result.feedback) end
                ok=true
            end
            if not ok then
                for _,candidate in ipairs(fars) do failed[candidate.key]=true end
                last_reason,last_failure="placement_stance_unavailable",result.feedback
            elseif not trapped then
                if drawing then walked_states[drawing]=true end
                local selected=result.data.selected_work_target
                if not selected and #fars>1 then return report("work_member_unconfirmed",round,nil,result.feedback) end
                selected=selected or far.cell
                local matched=false
                for _,candidate in ipairs(fars) do
                    local c=candidate.cell
                    if c.x==selected.x and c.y==selected.y and c.z==selected.z then
                        failed[candidate.key]=true
                        matched=true
                    end
                end
                if not matched then return report("work_member_unconfirmed",round,nil,result.feedback) end
                work_arrival=true
            end
        elseif pages[1].work_next_cursor and pages[1].work_next_cursor>=0 then
            cursor=pages[1].work_next_cursor
        elseif not repairing then
            repairing,cursor=true,0
        elseif recover(stamp,true) then
            cursor=0
        else
            -- A blocked build can still have throwaway supports that no longer
            -- carry any local dependency.  Give the bounded cleanup pass one
            -- chance before returning the obstruction to the model.
            cleanup_after_stall(true)
            local result=report(last_reason or "no_feasible_work_material_support_state_or_protection",round,pages)
            result.last_failure=last_failure
            return result
        end
    end
    local pages,reason=checked(target,revision)
    if pages and pages[1].goal_satisfied then
        local done,remaining=completed(rounds,pages)
        return done or report("cleanup_changed_target",rounds,remaining)
    end
    cleanup_after_stall(true)
    return report(reason or "round_limit",rounds,pages)
end

function mc.cleanup_temporary(revision)
    local site=call("build_site",{operation="query"}).data
    if not site.active or site.target_revision~=revision then
        return {removed=0,retained=site.temporary and #site.temporary or 0,
            blocked=site.active and "worksite_revision_mismatch" or "worksite_not_registered",
            target_revision=site.target_revision,requested_revision=revision,goal_satisfied=false}
    end
    local removed,attempted,recovery=0,{},{}
    local failures,failures_omitted={},0
    local function finish(blocked)
        local retained,reasons,reasons_omitted=0,{},0
        for _,record in ipairs(site.temporary) do
            if not record.present_known or record.present~=false then
                retained=retained+1
                if #reasons<8 and record.cleanup_reason then
                    reasons[#reasons+1]={x=record.x,y=record.y,z=record.z,
                        reason=record.cleanup_reason}
                elseif record.cleanup_reason then reasons_omitted=reasons_omitted+1
                end
            end
        end
        return {removed=removed,retained=retained,blocked=blocked or (#failures>0 and "some_stances_unavailable" or nil),
            failures=failures,failures_omitted=failures_omitted,retained_reasons=reasons,
            retained_reasons_omitted=reasons_omitted,goal_satisfied=false}
    end
    while true do
        local moved=false
        if site.body_on_temporary then
            local plan=site.relocation
            local ok,result=relocate(site,revision,"cleanup",recovery)
            moved=ok
            if plan and plan.operation=="lower" and plan.temporary_cleanup
                and result and result.data.removed then
                removed=removed+1
                if not ok then
                    site=call("build_site",{operation="query"}).data
                    return finish(true)
                end
            end
        end
        if not moved then
            local record=nil
            for _,candidate in ipairs(site.temporary) do
                local key=candidate.x..":"..candidate.y..":"..candidate.z
                if candidate.cleanup_allowed and not attempted[key]
                    and not (candidate.present_known and candidate.present==false)
                    and (not record or candidate.y>record.y) then record=candidate end
            end
            if not record then return finish() end
            attempted[record.x..":"..record.y..":"..record.z]=true
            -- Dismantling must not create another scaffold just to reach the previous one.
            local reachable,result=move(record,nil,site.bounds,true)
            if not reachable then
                if #failures<8 then
                    failures[#failures+1]={x=record.x,y=record.y,z=record.z,
                        reason=result and result.feedback}
                else failures_omitted=failures_omitted+1 end
                site=call("build_site",{operation="query"}).data
            else
                local fresh=call("build_site",{operation="query"}).data
                local allowed=false
                for _,r in ipairs(fresh.temporary) do
                    if r.x==record.x and r.y==record.y and r.z==record.z then allowed=r.cleanup_allowed end
                end
                if allowed then
                    local result=call("break_block",{x=record.x,y=record.y,z=record.z,expected_block=record.block,
                        site_revision=revision,temporary_cleanup=true})
                    if result.data.removed then removed=removed+1; attempted={} end
                end
            end
        end
        site=call("build_site",{operation="query"}).data
    end
end
