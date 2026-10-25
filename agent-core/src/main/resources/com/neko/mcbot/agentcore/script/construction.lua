local call, attempt = mc.call, mc.try_call

local function target_or_goal(target, revision)
    if target then return target,revision end
    local goal=mc.goal()
    assert(goal and goal.type=="building" and goal.target and goal.expected_revision)
    if revision then assert(revision==goal.expected_revision) end
    return goal.target,goal.expected_revision
end

local function move(cell, item, bounds)
    local p = {x=cell.x,y=cell.y,z=cell.z}
    local args = {x=p.x,y=p.y,z=p.z,work_target=p,exact=true,
        arrival="work",bounds=bounds}
    if item then args.place_item=item; args.face=cell.face; args.temporary_purpose=cell.purpose end
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
    local function remember(result)
        if result and (result.data.placed_blocks or 0)>0 then temporary_possible=true end
    end
    local placed,cleared,consumed=0,0,0
    local effects,omitted={},0
    local last_pages,initial_remaining=nil,nil
    local relocations,recovery_seen,cleanup=0,{},nil
    local function effect(cell,kind)
        if #effects<16 then
            effects[#effects+1]={x=cell.x,y=cell.y,z=cell.z,block=cell.block,kind=kind}
        else omitted=omitted+1 end
    end
    local function report(reason,round,pages,failure)
        local current=pages~=nil
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
        if cleanup then
            result.temporary_removed=cleanup.removed
            result.temporary_retained=cleanup.retained
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
        end
        print("mc.build",result)
        return result
    end
    local function completed(round,pages)
        if not temporary_possible then return report(nil,round,pages) end
        local current=call("build_site",{operation="query"}).data
        if current.temporary and #current.temporary>0 then
            cleanup=mc.cleanup_temporary(revision)
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
    local repairing=false
    local function recover(workstamp, required)
        site=call("build_site",{operation="query"}).data
        if required and not site.body_obstructs_target then return false end
        local ok,result=relocate(site,revision,workstamp,recovery_seen)
        remember(result)
        if ok then relocations=relocations+1 end
        return ok
    end
    while rounds==0 or round<rounds do
        round=round+1
        call("status",{details=true})
        local pages,reason=checked(target,revision,nil,cursor)
        if not pages then return report(reason,round) end
        local workstamp=pages[1].work_observation_key or pages[1].observation_key
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
            stamp=pages[1].work_observation_key or pages[1].observation_key
            failed,cursor,repairing={},0,true
        end
        -- Leaving a drawing cell is a local body action, rather than another remote work search.
        local moved=false
        if pages[1].body_obstructs_target or site.body_obstructs_target then moved=recover(stamp,true) end
        local ready,clear,far,clears=nil,nil,nil,{}
        for _,page in ipairs(pages) do
            for _,cell in ipairs(page.cells) do
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
                        if kind=="place" and lower(candidate,far) then far=candidate end
                    end
                end
            end
        end
        if not moved and (ready or clear or far) then
            call("build_site",{operation="checkpoint",expected_revision=revision})
        end
        if moved then
            cursor=0
        elseif clear and (not ready or clear.cell.y<=ready.cell.y) then
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
                                    expected_block=c.actual_block,site_revision=revision}).data
                                if dug.removed then cleared=cleared+1; effect(c,"cleared")
                                else failed[candidate.key]=true end
                            end
                        end
                    end
                end
            end
        elseif ready then
            local result=attempt("build_nearby",{target=target,expected_revision=revision,
                max_actions=actions,include_repairs=repairing})
            for _,page in ipairs(pages) do
                if page.work_scan or page.offset==ready.offset then
                    for _,cell in ipairs(page.cells) do
                        if cell.state=="ready" then failed[workkey(cell,"ready",stamp)]=true end
                    end
                end
            end
            placed=placed+(result.data.placed_blocks or 0)
            consumed=consumed+(result.data.consumed_count or 0)
            if result.data.placement_results then
                for _,cell in ipairs(result.data.placement_results) do
                    if cell.ok then effect(cell,"placed") end
                end
            end
            if not result.ok then
                call("status",{details=true})
                failed[ready.key]=true
                last_reason,last_failure="nearby_work_unavailable",result.feedback
            elseif (result.data.placed_blocks or 0)==0 then
                failed[ready.key]=true
                last_reason="nearby_no_actual_progress"
            end
        elseif far then
            local ok,result,trapped=move(far.cell,far.cell.item,site.bounds)
            remember(result)
            if trapped then
                if not recover(stamp) then return report("departure_blocked",round,pages,result.feedback) end
                ok=true
            end
            if not ok then
                failed[far.key]=true
                last_reason,last_failure="placement_stance_unavailable",result.feedback
            else
                failed[far.key]=true
            end
        elseif pages[1].work_next_cursor and pages[1].work_next_cursor>=0 then
            cursor=pages[1].work_next_cursor
        elseif not repairing then
            repairing,cursor=true,0
        elseif recover(stamp) then
            cursor=0
        else
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
    return report(reason or "round_limit",rounds,pages)
end

function mc.cleanup_temporary(revision)
    local site=call("build_site",{operation="query"}).data
    assert(site.active and site.target_revision==revision)
    if site.body_on_temporary then
        relocate(site,revision,"cleanup",{})
        site=call("build_site",{operation="query"}).data
    end
    local removed,retained=0,0
    for _,record in ipairs(site.temporary) do
        if record.present_known and record.present==false then
            -- A consumed provenance slot is retained, but a missing block is not retained world material.
        elseif record.cleanup_allowed then
            if not move(record,nil,site.bounds) then return {removed=removed,retained=retained,blocked=true} end
            local fresh=call("build_site",{operation="query"}).data
            local allowed=false
            for _,r in ipairs(fresh.temporary) do
                if r.x==record.x and r.y==record.y and r.z==record.z then allowed=r.cleanup_allowed end
            end
            if allowed then
                local result=call("break_block",{x=record.x,y=record.y,z=record.z,expected_block=record.block,
                    site_revision=revision,temporary_cleanup=true})
                if result.data.removed then removed=removed+1 end
            else retained=retained+1 end
        else retained=retained+1 end
    end
    return {removed=removed,retained=retained,goal_satisfied=false}
end
