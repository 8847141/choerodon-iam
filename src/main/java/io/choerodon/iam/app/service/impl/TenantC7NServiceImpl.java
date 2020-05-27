package io.choerodon.iam.app.service.impl;

import static io.choerodon.iam.infra.utils.SagaTopic.Organization.ORG_DISABLE;
import static io.choerodon.iam.infra.utils.SagaTopic.Organization.ORG_ENABLE;

import java.util.*;
import java.util.stream.Collectors;

import org.apache.commons.collections4.CollectionUtils;
import org.hzero.boot.message.MessageClient;
import org.hzero.iam.api.dto.TenantDTO;
import org.hzero.iam.app.service.TenantService;
import org.hzero.iam.domain.entity.Role;
import org.hzero.iam.domain.entity.Tenant;
import org.hzero.iam.domain.entity.TenantConfig;
import org.hzero.iam.domain.entity.User;
import org.hzero.iam.domain.repository.TenantConfigRepository;
import org.hzero.iam.domain.repository.TenantRepository;
import org.hzero.iam.infra.common.utils.UserUtils;
import org.hzero.iam.infra.mapper.TenantMapper;
import org.hzero.iam.infra.mapper.UserMapper;
import org.hzero.mybatis.domian.Condition;
import org.hzero.mybatis.util.Sqls;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import io.choerodon.core.domain.Page;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.exception.ext.UpdateException;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.oauth.CustomUserDetails;
import io.choerodon.iam.api.vo.ProjectOverViewVO;
import io.choerodon.iam.api.vo.TenantConfigVO;
import io.choerodon.iam.api.vo.TenantVO;
import io.choerodon.iam.app.service.TenantC7nService;
import io.choerodon.iam.app.service.UserC7nService;
import io.choerodon.iam.infra.asserts.OrganizationAssertHelper;
import io.choerodon.iam.infra.dto.ProjectDTO;
import io.choerodon.iam.infra.feign.AsgardFeignClient;
import io.choerodon.iam.infra.feign.DevopsFeignClient;
import io.choerodon.iam.infra.mapper.*;
import io.choerodon.iam.infra.utils.ConvertUtils;
import io.choerodon.iam.infra.utils.TenantConfigConvertUtils;
import io.choerodon.mybatis.pagehelper.PageHelper;
import io.choerodon.mybatis.pagehelper.domain.PageRequest;

/**
 * @author scp
 * @date 2020/4/21
 * @description
 */
@Service
public class TenantC7NServiceImpl implements TenantC7nService {
    public static final String ORGANIZATION_DOES_NOT_EXIST_EXCEPTION = "error.organization.does.not.exist";
    public static final String ERROR_TENANT_PARAM_IS_NULL = "error.tenant.param.is.null";
    public static final String ERROR_TENANT_USERID_IS_NULL = "error.tenant.user.id.is.null";
    public static final String ORGANIZATION_LIMIT_DATE = "2020-03-24";


    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantC7nMapper tenantC7nMapper;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private ProjectMapper projectMapper;
    @Autowired
    private RoleC7nMapper roleC7nMapper;
    @Autowired
    private UserC7nMapper userC7nMapper;
    @Autowired
    private OrganizationAssertHelper organizationAssertHelper;
    @Autowired
    private AsgardFeignClient asgardFeignClient;
    @Autowired
    private DevopsFeignClient devopsFeignClient;
    // 注入messageClient
    @Autowired
    protected MessageClient messageClient;
    @Autowired
    private TenantMapper tenantMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private TenantConfigRepository tenantConfigRepository;
    @Autowired
    private UserC7nService userC7nService;
    @Autowired
    TenantConfigC7nMapper tenantConfigMapper;

    private TenantConfigC7nMapper tenantConfigC7nMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTenant(Long tenantId, TenantVO tenantVO) {
        List<TenantConfig> tenantConfigs = TenantConfigConvertUtils.tenantConfigVOToTenantConfigList(tenantId, tenantVO.getTenantConfigVO());
        Tenant tenant = getTenant(tenantVO);
        tenant.setTenantId(tenantId);
        if (tenantRepository.updateByPrimaryKeySelective(tenant) != 1) {
            throw new CommonException("error.tenant.update");
        }
        if (CollectionUtils.isEmpty(tenantConfigs)) {
            return;
        }
        for (TenantConfig tenantConfig : tenantConfigs) {
            TenantConfig record = new TenantConfig();
            record.setTenantId(tenantId);
            record.setConfigKey(tenantConfig.getConfigKey());
            TenantConfig selectOne = tenantConfigRepository.selectOne(record);
            if (Objects.isNull(selectOne)) {
                if (tenantConfigRepository.insert(tenantConfig) != 1) {
                    throw new CommonException("error.tenant.update");
                }
            } else {
                selectOne.setConfigValue(tenantConfig.getConfigValue());
                tenantConfigRepository.updateByPrimaryKeySelective(selectOne);
            }
        }
    }


    @Override
    public TenantVO queryTenantById(Long tenantId) {
        Tenant tenant = tenantService.queryTenant(tenantId);
        TenantVO tenantVO = ConvertUtils.convertObject(tenant, TenantVO.class);
        List<TenantConfig> tenantConfigList = tenantConfigRepository.selectByCondition(Condition.builder(TenantConfig.class)
                .where(Sqls.custom()
                        .andEqualTo(TenantConfig.FIELD_TENANT_ID, tenantId)
                )
                .build());
        TenantConfigVO tenantConfigVO = TenantConfigConvertUtils.configDTOToVO(tenantConfigList);
        tenantVO.setTenantConfigVO(tenantConfigVO);
        //返回组织所有者的手机号邮箱
        if (tenantConfigVO.getUserId() != null) {
            User user = userMapper.selectByPrimaryKey(tenantConfigVO.getUserId());
            if (user != null) {
                tenantVO.setOwnerRealName(user.getRealName());
                tenantVO.setOwnerEmail(user.getEmail());
                tenantVO.setOwnerPhone(user.getPhone());
                tenantVO.setOwnerLoginName(user.getLoginName());
            }
        }
        return tenantVO;
    }

    @Override
    public List<TenantVO> queryTenantByName(String tenantName) {
        Tenant tenant = new Tenant();
        tenant.setTenantName(tenantName);
        return ConvertUtils.convertList(tenantRepository.select(tenant), TenantVO.class);
    }

    @Override
    public TenantVO queryTenantWithRoleById(Long tenantId) {
        CustomUserDetails customUserDetails = UserUtils.getUserDetails();
        TenantVO dto = ConvertUtils.convertObject(tenantService.queryTenant(tenantId), TenantVO.class);
        long userId = customUserDetails.getUserId();
        List<TenantConfig> configList = tenantConfigRepository.select(new TenantConfig().setTenantId(tenantId));
        TenantConfigVO tenantConfigVO = TenantConfigConvertUtils.configDTOToVO((configList));
        dto.setTenantConfigVO(tenantConfigVO);
        //添加组织所有者信息
        if (!Objects.isNull(tenantConfigVO.getUserId())) {
            User user = userMapper.selectByPrimaryKey(tenantConfigVO.getUserId());
            if (!Objects.isNull(user)) {
                dto.setOwnerRealName(user.getRealName());
                dto.setOwnerLoginName(user.getLoginName());
                dto.setOwnerPhone(user.getPhone());
                dto.setOwnerEmail(user.getEmail());
            }
        }
        List<ProjectDTO> projects = projectMapper.selectUserProjectsUnderOrg(userId, tenantId, null);
        dto.setProjects(projects);
        dto.setProjectCount(projects.size());

        List<Role> roles = roleC7nMapper.queryRolesInfoByUser(ResourceLevel.ORGANIZATION.value(), tenantId, userId);
        dto.setRoles(roles);
        return dto;
    }

    @Override
    public Page<TenantVO> pagingQuery(PageRequest pageRequest, String name, String code, String ownerRealName, Boolean enabled, String params) {
        Page<TenantVO> tenantVOPage = PageHelper.doPage(pageRequest, () -> tenantC7nMapper.fulltextSearch(name, code, ownerRealName, enabled, params));
        tenantVOPage.getContent().forEach(
                tenantVO -> {
                    List<TenantConfig> tenantConfigList = tenantConfigRepository.selectByCondition(Condition.builder(TenantConfig.class)
                            .where(Sqls.custom()
                                    .andEqualTo(TenantConfig.FIELD_TENANT_ID, tenantVO.getTenantId())
                            )
                            .build());
                    TenantConfigVO tenantConfigVO = TenantConfigConvertUtils.configDTOToVO(tenantConfigList);
                    tenantVO.setTenantConfigVO(tenantConfigVO);
                    if (tenantConfigVO.getUserId() != null) {
                        User user = userMapper.selectByPrimaryKey(tenantConfigVO.getUserId());
                        if (user != null) {
                            tenantVO.setOwnerRealName(user.getRealName());
                            tenantVO.setOwnerEmail(user.getEmail());
                            tenantVO.setOwnerPhone(user.getPhone());
                            tenantVO.setOwnerLoginName(user.getLoginName());
                        }
                    }
                }
        );
        return tenantVOPage;
    }

    private List<TenantVO> fillTenant(List<TenantVO> content) {
        if (CollectionUtils.isEmpty(content)) {
            return null;
        }
        content.forEach(tenantVO -> {
            Tenant tenant = tenantRepository.selectTenantDetails(tenantVO.getTenantId());
            TenantConfigVO tenantConfigVO = TenantConfigConvertUtils.configDTOToVO(tenant.getTenantConfigs());
            if (Objects.isNull(tenantConfigVO)) {
                return;
            }
            tenantVO.setTenantConfigVO(tenantConfigVO);
            if (Objects.isNull(tenantConfigVO.getUserId())) {
                return;
            }
            User user = userMapper.selectByPrimaryKey(Long.valueOf(tenantConfigVO.getUserId()));
            if (!Objects.isNull(user)) {
                tenantVO.setOwnerEmail(user.getEmail());
                tenantVO.setOwnerLoginName(user.getLoginName());
                tenantVO.setOwnerPhone(user.getPhone());
                tenantVO.setOwnerRealName(user.getRealName());
            }
        });
        return content;
    }

    @Override
    public Page<TenantVO> getAllTenants(PageRequest pageRequest) {
        return PageHelper.doPageAndSort(pageRequest, () -> tenantRepository.selectAll());
    }

    @Override
    public Tenant enableOrganization(Long organizationId, Long userId) {
        Tenant organization = organizationAssertHelper.notExisted(organizationId);
        organization.setEnabledFlag(1);
        return updateAndSendEvent(organization, ORG_ENABLE, userId);
    }

    @Override
    public Tenant disableOrganization(Long organizationId, Long userId) {
        Tenant organizationDTO = organizationAssertHelper.notExisted(organizationId);
        organizationDTO.setEnabledFlag(0);
        return updateAndSendEvent(organizationDTO, ORG_DISABLE, userId);
    }

    @Override
    public void check(TenantVO tenantVO) {
        Boolean checkCode = !StringUtils.isEmpty(tenantVO.getTenantNum());
        if (!checkCode) {
            throw new CommonException("error.organization.code.empty");
        } else {
            checkCode(tenantVO);
        }
    }

    @Override
    public Page<User> pagingQueryUsersInOrganization(Long organizationId, Long userId, String email, PageRequest pageRequest, String param) {
        return PageHelper.doPageAndSort(pageRequest, () -> userC7nMapper.selectUsersByLevelAndOptions(ResourceLevel.ORGANIZATION.value(), organizationId, userId, email, param));
    }

    @Override
    public Page<Tenant> pagingSpecified(Set<Long> orgIds, String name, String code, Boolean enabled, String params, PageRequest pageable) {
        if (CollectionUtils.isEmpty(orgIds)) {
            return new Page<>();
        }
        return PageHelper.doPageAndSort(pageable, () -> tenantC7nMapper.selectSpecified(orgIds, name, code, enabled, params));
    }

    @Override
    public ProjectOverViewVO projectOverview(Long organizationId) {
        ProjectOverViewVO projectOverViewVO = tenantC7nMapper.projectOverview(organizationId);
        if (projectOverViewVO == null) {
            return new ProjectOverViewVO(0, 0);
        }
        return projectOverViewVO;
    }

    @Override
    public List<ProjectOverViewVO> appServerOverview(Long organizationId) {
        ProjectDTO projectDTO = new ProjectDTO();
        projectDTO.setOrganizationId(organizationId);
        List<ProjectDTO> projectDTOS = projectMapper.select(projectDTO);
        if (org.springframework.util.CollectionUtils.isEmpty(projectDTOS)) {
            return Collections.emptyList();
        }
        List<ProjectOverViewVO> projectOverViewVOS = new ArrayList<>();
        List<Long> longList = projectDTOS.stream().map(ProjectDTO::getId).collect(Collectors.toList());
        Map<Long, Integer> map = devopsFeignClient.countAppServerByProjectId(longList.get(0), longList).getBody();
        projectDTOS.stream().distinct().forEach(v -> {
            ProjectOverViewVO projectOverViewVO = new ProjectOverViewVO();
            projectOverViewVO.setId(v.getId());
            projectOverViewVO.setProjectName(v.getName());
            projectOverViewVO.setAppServerSum(map.get(v.getId()));
            projectOverViewVOS.add(projectOverViewVO);
        });
        List<ProjectOverViewVO> collect = projectOverViewVOS
                .stream()
                .sorted(Comparator.comparing(ProjectOverViewVO::getAppServerSum).reversed())
                .collect(Collectors.toList());
        List<ProjectOverViewVO> reOverViewVOS = new ArrayList<>();
        List<ProjectOverViewVO> temOverViewVOS = new ArrayList<>();
        collect.forEach(projectOverViewVO -> {
            if (reOverViewVOS.size() < 24) {
                reOverViewVOS.add(projectOverViewVO);
            }
            if (reOverViewVOS.size() >= 24) {
                temOverViewVOS.add(projectOverViewVO);
            }
        });
        ProjectOverViewVO projectOverViewVO1 = new ProjectOverViewVO();
        int sum = temOverViewVOS.stream().mapToInt(ProjectOverViewVO::getAppServerSum).sum();
        //项目数量不足9个不要其他剩余
        if (reOverViewVOS.size() <= 9) {
            return reOverViewVOS;
        }
        projectOverViewVO1.setProjectName("其他剩余");
        projectOverViewVO1.setAppServerSum(sum);
        reOverViewVOS.add(projectOverViewVO1);
        return reOverViewVOS;
    }


    @Override
    public List<Tenant> queryTenantsByIds(Set<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyList();
        }
        return tenantC7nMapper.selectByIds(ids);
    }

    @Override
    public List<TenantVO> selectSelfTenants(TenantDTO params) {
        CustomUserDetails self = UserUtils.getUserDetails();
        params.setUserId(self.getUserId());
        return listOwnedOrganizationByTenant(params);
    }

    @Override
    public List<TenantVO> listOwnedOrganizationByUserId(Long userId) {
        TenantDTO params = new TenantDTO();
        params.setUserId(userId);
        return listOwnedOrganizationByTenant(params);
    }

    @Override
    public int countUserNum(Long organizationId) {
        User example = new User();
        example.setOrganizationId(organizationId);
        return userMapper.selectCount(example);
    }

    @Override
    public int countProjectNum(Long organizationId) {
        ProjectDTO example = new ProjectDTO();
        example.setOrganizationId(organizationId);
        return projectMapper.selectCount(example);
    }

    /**
     * 查询用户可访问的组织，into判断是否可进
     *
     * @param params
     * @return
     */
    private List<TenantVO> listOwnedOrganizationByTenant(TenantDTO params) {
        Assert.notNull(params, ERROR_TENANT_PARAM_IS_NULL);
        Assert.notNull(params.getUserId(), ERROR_TENANT_USERID_IS_NULL);
        List<TenantDTO> tenantDTOS = tenantMapper.selectUserTenant(params);
        // 过滤hzero平台组织
        if (CollectionUtils.isEmpty(tenantDTOS)) {
            return new ArrayList<>();
        }
        tenantDTOS = tenantDTOS.stream().filter(tenantDTO -> tenantDTO.getTenantId() == 0).collect(Collectors.toList())
        User user = userMapper.selectByPrimaryKey(params.getUserId());
        return getOwnedOrganizations(user.getId(), Boolean.TRUE.equals(user.getAdmin()), tenantDTOS);
    }

    /**
     * 计算into字段
     */
    private List<TenantVO> getOwnedOrganizations(Long userId, boolean isAdmin, List<TenantDTO> tenantDTOS) {
        List<TenantVO> tenantVOS = ConvertUtils.convertList(tenantDTOS, TenantVO.class);

        if (isAdmin) {
            tenantVOS.forEach(tenantVO -> tenantVO.setInto(true));
        } else {
            Set<Long> orgIds = tenantVOS.stream().map(Tenant::getTenantId).collect(Collectors.toSet());
            Set<Long> managedOrgIds = userC7nMapper.listManagedOrgIdByUserId(userId, orgIds);
            Map<Long, TenantVO> tenantVOMap = tenantVOS.stream().collect(Collectors.toMap(Tenant::getTenantId, v -> v));
            managedOrgIds.forEach(orgId -> {
                TenantVO tenantVO = tenantVOMap.get(orgId);
                if (tenantVO != null) {
                    tenantVO.setInto(true);
                }
            });
        }
        return tenantVOS;
    }

    private void checkCode(TenantVO tenantVO) {
        Boolean createCheck = StringUtils.isEmpty(tenantVO.getTenantId());
        Tenant tenant = getTenant(tenantVO);
        if (createCheck) {
            Boolean existed = tenantRepository.selectOne(tenant) != null;
            if (existed) {
                throw new CommonException("error.organization.code.exist");
            }
        } else {
            Long id = tenantVO.getTenantId();
            Tenant dto = tenantRepository.selectOne(tenant);
            Boolean existed = dto != null && !id.equals(dto.getTenantId());
            if (existed) {
                throw new CommonException("error.organization.code.exist");
            }
        }
    }

    private Tenant updateAndSendEvent(Tenant tenant, String consumerType, Long userId) {
        Tenant organizationDTO = doUpdate(tenant);

        //给asgard发送禁用定时任务通知
        asgardFeignClient.disableOrg(tenant.getTenantId());

        // todo webhook消息发送
        // 给组织下所有用户发送通知
//
//        // 准备消息发送的messageSender
//        MessageSender messageSender=new MessageSender();
//        // 消息code
//        messageSender.setMessageCode(MessageCodeConstants.DISABLE_ORGANIZATION);
//        // 默认为0L,都填0L,可不填写
//        messageSender.setTenantId(0L);
//
//        // 消息参数 消息模板中${projectName}
//        Map<String,String> argsMap=new HashMap<>();
//        argsMap.put("projectName","testProject");
//        argsMap.put("orgCode","testOrganization");
//        argsMap.put("orgName","测试组织");
//        messageSender.setArgs(argsMap);
//
//        //额外参数，用于逻辑过滤 包括项目id，环境id，devops的消息事件
//        Map<String,Object> objectMap=new HashMap<>();
//        objectMap.put(MessageAdditionalType.PARAM_PROJECT_ID.getTypeName(),1L);
//        objectMap.put(MessageAdditionalType.PARAM_ENV_ID.getTypeName(),1L);
//        objectMap.put(MessageAdditionalType.PARAM_EVENT_NAME.getTypeName(),"service");
//        messageSender.setAdditionalInformation(objectMap);
//
//        // 接收者
//        List<Receiver> receiverList=new ArrayList<>();
//        Receiver receiver=new Receiver();
//        receiver.setUserId(1L);
//        // 发送邮件消息时 必填
//        receiver.setEmail("xxx.qq.com");
//        // 发送短信消息 必填
//        receiver.setPhone("176666");
//        receiverList.add(receiver);
//        messageSender.setReceiverAddressList(receiverList);
//
//        messageClient.async().sendMessage(messageSender);
//        List<Long> userIds = tenantC7nMapper.listMemberIds(tenant.getTenantId(), "organization");
//        Map<String, Object> params = new HashMap<>();
//        params.put("organizationName", organizationDTO.getTenantName());
//        JSONObject jsonObject = new JSONObject();
//        jsonObject.put("organizationId", organizationDTO.getTenantId());
//        jsonObject.put("code", organizationDTO.getTenantNum());
//        jsonObject.put("name", organizationDTO.getTenantName());
//        jsonObject.put("enabled", organizationDTO.getEnabledFlag());
//        if (ORG_DISABLE.equals(consumerType)) {
//
//                WebHookJsonSendDTO webHookJsonSendDTO = new WebHookJsonSendDTO(
//                        SendSettingBaseEnum.DISABLE_ORGANIZATION.value(),
//                        SendSettingBaseEnum.map.get(SendSettingBaseEnum.DISABLE_ORGANIZATION.value()),
//                        jsonObject
//                        organizationDTO.getCreationDate(),
//                        userService.getWebHookUser(organizationDTO.getCreatedBy())
//                );
//                userService.sendNotice(userId, userIds, "disableOrganization", params, organization.getId(), webHookJsonSendDTO);
//        } else if (ORG_ENABLE.equals(consumerType)) {
//
//                WebHookJsonSendDTO webHookJsonSendDTO = new WebHookJsonSendDTO(
//                        SendSettingBaseEnum.ENABLE_ORGANIZATION.value(),
//                        SendSettingBaseEnum.map.get(SendSettingBaseEnum.ENABLE_ORGANIZATION.value()),
//                        jsonObject,
//                        organizationDTO.getCreationDate(),
//                        userService.getWebHookUser(organizationDTO.getCreatedBy())
//                );
//                userService.sendNotice(userId, userIds, "enableOrganization", params, organization.getId(), webHookJsonSendDTO);
//        }
        return organizationDTO;
    }


    private Tenant doUpdate(Tenant tenant) {
        if (tenantRepository.updateByPrimaryKeySelective(tenant) != 1) {
            throw new UpdateException("error.organization.update");
        }
        return tenantRepository.selectByPrimaryKey(tenant);
    }

    private Tenant getTenant(TenantVO tenantVO) {
        Tenant tenant = new Tenant();
        BeanUtils.copyProperties(tenantVO, tenant);
        return tenant;
    }
}
