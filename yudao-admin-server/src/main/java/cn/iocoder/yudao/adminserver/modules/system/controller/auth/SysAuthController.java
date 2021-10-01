package cn.iocoder.yudao.adminserver.modules.system.controller.auth;

import cn.hutool.core.net.url.UrlBuilder;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.adminserver.modules.system.enums.user.SysUserSocialTypeEnum;
import cn.iocoder.yudao.adminserver.modules.system.service.auth.SysUserSessionService;
import cn.iocoder.yudao.framework.common.enums.CommonStatusEnum;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.http.HttpUtils;
import cn.iocoder.yudao.framework.operatelog.core.annotations.OperateLog;
import cn.iocoder.yudao.adminserver.modules.system.controller.auth.vo.auth.SysAuthLoginReqVO;
import cn.iocoder.yudao.adminserver.modules.system.controller.auth.vo.auth.SysAuthLoginRespVO;
import cn.iocoder.yudao.adminserver.modules.system.controller.auth.vo.auth.SysAuthMenuRespVO;
import cn.iocoder.yudao.adminserver.modules.system.controller.auth.vo.auth.SysAuthPermissionInfoRespVO;
import cn.iocoder.yudao.adminserver.modules.system.convert.auth.SysAuthConvert;
import cn.iocoder.yudao.adminserver.modules.system.dal.dataobject.permission.SysMenuDO;
import cn.iocoder.yudao.adminserver.modules.system.dal.dataobject.permission.SysRoleDO;
import cn.iocoder.yudao.adminserver.modules.system.dal.dataobject.user.SysUserDO;
import cn.iocoder.yudao.adminserver.modules.system.enums.permission.MenuTypeEnum;
import cn.iocoder.yudao.adminserver.modules.system.service.auth.SysAuthService;
import cn.iocoder.yudao.adminserver.modules.system.service.permission.SysPermissionService;
import cn.iocoder.yudao.adminserver.modules.system.service.permission.SysRoleService;
import cn.iocoder.yudao.adminserver.modules.system.service.user.SysUserService;
import cn.iocoder.yudao.framework.common.util.collection.SetUtils;
import com.aliyuncs.CommonResponse;
import com.xkcoding.justauth.AuthRequestFactory;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import me.zhyd.oauth.utils.AuthStateUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.SimpleTrigger;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriBuilder;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;
import static cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils.getLoginUserRoleIds;
import static cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getClientIP;
import static cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getUserAgent;

@Api(tags = "认证")
@RestController
@RequestMapping("/")
@Validated
@Slf4j
public class SysAuthController {

    @Resource
    private SysAuthService authService;
    @Resource
    private SysUserService userService;
    @Resource
    private SysRoleService roleService;
    @Resource
    private SysPermissionService permissionService;
    @Resource
    private SysUserSessionService sysUserSessionService;

    @Resource
    private AuthRequestFactory authRequestFactory;

    @PostMapping("/login")
    @ApiOperation("使用账号密码登录")
    @OperateLog(enable = false) // 避免 Post 请求被记录操作日志
    public CommonResult<SysAuthLoginRespVO> login(@RequestBody @Valid SysAuthLoginReqVO reqVO) {
        String token = authService.login(reqVO, getClientIP(), getUserAgent());
        // 返回结果
        return success(SysAuthLoginRespVO.builder().token(token).build());
    }

    @GetMapping("/third-login-redirect")
    @ApiOperation("三方登陆的跳转")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "type", value = "三方类型", required = true, dataTypeClass = Integer.class),
            @ApiImplicitParam(name = "redirectUri", value = "回调路径", dataTypeClass = String.class)
    })
    public CommonResult<String> login(@RequestParam("type") Integer type,
                                      @RequestParam("redirectUri") String redirectUri) throws IOException {
        // 获得对应的 AuthRequest 实现
        AuthRequest authRequest = authRequestFactory.get(SysUserSocialTypeEnum.valueOfType(type).getSource());
        // 生成跳转地址
        String authorizeUri = authRequest.authorize(AuthStateUtils.createState());
        authorizeUri = HttpUtils.replaceUrlQuery(authorizeUri, "redirect_uri", redirectUri);
//        authorizeUri = UrlBuilder.fromBaseUrl(authorizeUri).queryParam("redirect_uri", redirectUri).build();
        return CommonResult.success(authorizeUri);
    }

    @RequestMapping("/{type}/callback")
    public AuthResponse login(@PathVariable String type, AuthCallback callback) {
        AuthRequest authRequest = authRequestFactory.get(type);
        AuthResponse<AuthUser> response = authRequest.login(callback);
        log.info("【response】= {}", JSONUtil.toJsonStr(response));
        return response;
    }

//    @RequestMapping("/auth2/login/{oauthType}")
//    @ApiOperation("第三方登录")
//    @OperateLog(enable = false) // 避免 Post 请求被记录操作日志
//    public CommonResult<SysAuthLoginRespVO> login(@PathVariable String oauthType) {
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        //TODO NPE
//        String token = sysUserSessionService.getSessionId(authentication.getName());
//        // 返回结果
//        return success(SysAuthLoginRespVO.builder().token(token).build());
//    }

    @GetMapping("/get-permission-info")
    @ApiOperation("获取登陆用户的权限信息")
    public CommonResult<SysAuthPermissionInfoRespVO> getPermissionInfo() {
        // 获得用户信息
        SysUserDO user = userService.getUser(getLoginUserId());
        if (user == null) {
            return null;
        }
        // 获得角色列表
        List<SysRoleDO> roleList = roleService.getRolesFromCache(getLoginUserRoleIds());
        // 获得菜单列表
        List<SysMenuDO> menuList = permissionService.getRoleMenusFromCache(
                getLoginUserRoleIds(), // 注意，基于登陆的角色，因为后续的权限判断也是基于它
                SetUtils.asSet(MenuTypeEnum.DIR.getType(), MenuTypeEnum.MENU.getType(), MenuTypeEnum.BUTTON.getType()),
                SetUtils.asSet(CommonStatusEnum.ENABLE.getStatus()));
        // 拼接结果返回
        return success(SysAuthConvert.INSTANCE.convert(user, roleList, menuList));
    }

    @GetMapping("list-menus")
    @ApiOperation("获得登陆用户的菜单列表")
    public CommonResult<List<SysAuthMenuRespVO>> getMenus() {
        // 获得用户拥有的菜单列表
        List<SysMenuDO> menuList = permissionService.getRoleMenusFromCache(
                getLoginUserRoleIds(), // 注意，基于登陆的角色，因为后续的权限判断也是基于它
                SetUtils.asSet(MenuTypeEnum.DIR.getType(), MenuTypeEnum.MENU.getType()), // 只要目录和菜单类型
                SetUtils.asSet(CommonStatusEnum.ENABLE.getStatus())); // 只要开启的
        // 转换成 Tree 结构返回
        return success(SysAuthConvert.INSTANCE.buildMenuTree(menuList));
    }

}