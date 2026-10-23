local call, attempt = mc.call, mc.try_call

local function blocked(reason, revision)
    return {goal_satisfied=false, blocked=reason, target_revision=revision}
end

local function move(cell, item, bounds)
    local p = {x=cell.x,y=cell.y,z=cell.z}
    local args = {x=p.x,y=p.y,z=p.z,work_target=p,exact=true,
        arrival="work",walk_only=true,bounds=bounds}
    if item then args.place_item=item; args.face=cell.face or "up"; args.temporary_purpose=cell.purpose end
    local result = attempt("move_to", args)
    -- Only the bridge's existing local-failure policy can return an unsuccessful result.
    if not result.ok then
        if result.failure_kind=="TARGET_LOST" then error("unknown work facts") end
        call("status",{details=true})
    end
    return result.ok
end

local function checked(target, revision)
    local pages, offset, stamp = {}, 0, nil
    for page=1,8 do
        local d=call("build_diff",{target=target,expected_revision=revision,offset=offset}).data
        if d.target_revision~=revision or not d.observation_complete or not d.spatial_stable
            or d.budget_exhausted or not d.material_bill_complete then
            return nil,"incomplete_or_unstable_observation"
        end
        if stamp and stamp~=d.observation_key then return nil,"world_or_material_changed_between_pages" end
        stamp=d.observation_key
        if d.goal_satisfied then return {d} end
        pages[#pages+1]=d
        if d.next_offset==-1 then return pages end
        offset=d.next_offset
    end
    return nil,"page_limit"
end

function mc.build(target, options)
    local o=options or {}
    local rounds, actions=o.max_rounds or 0,o.max_actions or 4
    assert(type(rounds)=="number" and rounds>=0 and rounds<=2147483647 and rounds%1==0)
    assert(type(actions)=="number" and actions>=1 and actions<=12 and actions%1==0)
    assert(type(o.access_route)=="table")
    for key in pairs(o) do
        assert(key=="access_route" or key=="bounds" or key=="max_rounds"
            or key=="max_actions" or key=="temporary")
    end
    local site=call("build_site",{operation="begin",target=target,
        access_route=o.access_route,bounds=o.bounds}).data
    local revision=site.target_revision
    local temporary=o.temporary or {}
    assert(type(temporary)=="table" and #temporary<=16)
    if #temporary>0 then
        local pages,reason=checked(target,revision)
        if not pages then return blocked(reason,revision) end
        if pages[1].goal_satisfied then
            return {goal_satisfied=true,target_revision=revision,rounds=0,
                evidence="fresh_whole_target_diff",outer_goal_verified=false}
        end
    end
    for _,cell in ipairs(temporary) do
        assert(cell.purpose=="support" or cell.purpose=="workstation")
        local existing=nil
        for _,record in ipairs(site.temporary) do
            if record.x==cell.x and record.y==cell.y and record.z==cell.z then existing=record end
        end
        if existing then
            if not existing.provenance_intact then return blocked("temporary_provenance_conflict",revision) end
        else
            if not move(cell,cell.item,site.bounds) then return blocked("temporary_stance_unavailable",revision) end
            call("place_block",{x=cell.x,y=cell.y,z=cell.z,item=cell.item,
                face=cell.face or "up",temporary_purpose=cell.purpose})
            site=call("build_site",{operation="query"}).data
        end
    end
    local round=0
    while rounds==0 or round<rounds do
        round=round+1
        call("status",{details=true})
        local pages,reason=checked(target,revision)
        if not pages then return blocked(reason,revision) end
        if pages[1].goal_satisfied then
            return {goal_satisfied=true,target_revision=revision,rounds=round-1,
                evidence="fresh_whole_target_diff",outer_goal_verified=false}
        end
        call("build_site",{operation="checkpoint",expected_revision=revision})
        local ready,clear,far=nil,nil,nil
        for _,page in ipairs(pages) do
            for _,cell in ipairs(page.cells) do
                if cell.state=="ready" and not ready then ready=page.offset end
                if cell.state=="needs_clear" and cell.clear_allowed and not clear then
                    clear={cell=cell,offset=page.offset}
                end
                if (cell.state=="out_of_reach" or cell.state=="blocked") and cell.item and cell.material_available
                    and not far then far=cell end
            end
        end
        if ready then
            call("build_nearby",{target=target,expected_revision=revision,offset=ready,max_actions=actions})
        elseif clear then
            local cell=clear.cell
            if move(cell,nil,site.bounds) then
                -- Arrival does not authorize clearing a replacement or a newly completed cell.
                local fresh=call("build_diff",{target=target,expected_revision=revision,offset=clear.offset}).data
                if not fresh.observation_complete or not fresh.spatial_stable or fresh.budget_exhausted then
                    return blocked("clear_observation_incomplete",revision)
                end
                for _,c in ipairs(fresh.cells) do
                    if c.x==cell.x and c.y==cell.y and c.z==cell.z and c.state=="needs_clear"
                        and c.clear_allowed and c.actual_block==cell.actual_block then
                        call("break_block",{x=c.x,y=c.y,z=c.z,expected_block=c.actual_block,site_revision=revision})
                    end
                end
            end
        elseif far then
            move(far,far.item,site.bounds)
        else
            return blocked("no_feasible_work_material_support_state_or_protection",revision)
        end
    end
    local pages,reason=checked(target,revision)
    if pages and pages[1].goal_satisfied then
        return {goal_satisfied=true,target_revision=revision,rounds=rounds,
            evidence="fresh_whole_target_diff",outer_goal_verified=false}
    end
    return blocked(reason or "round_limit",revision)
end

function mc.cleanup_temporary(revision)
    local site=call("build_site",{operation="query"}).data
    assert(site.active and site.target_revision==revision)
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
