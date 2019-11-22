drop procedure if exists ui_get_action_by_name_for_subsystem;
delimiter $$
create procedure ui_get_action_by_name_for_subsystem(i_action_name varchar(32), i_ssys_id int(10))
 main_sql:
  begin
    select a.actn_id,
           a.action_name,
           a.action_description,
           a.log_action,
           ss.ssys_id subsystem_ssys_id,
           ss.subsystem_name
      from actions a, subsystems ss
     where a.ssys_ssys_id = ss.ssys_id and a.action_name = i_action_name;
  end
$$
delimiter ;
call save_routine_information('ui_get_action_by_name_for_subsystem',
                              concat_ws(',',
                                        'actn_id int',
                                        'action_name varchar',
                                        'action_description varchar',
                                        'log_action varchar',
                                        'subsystem_ssys_id int',
                                        'subsystem_name varchar'
                              )
     );
