# 身份与资料边界

## 覆盖包

- `src/main/java/com/tongji/auth/**`
- `src/main/java/com/tongji/user/**`
- `src/main/java/com/tongji/profile/**`

## 主要入口

- `src/main/java/com/tongji/auth/api/AuthController.java`
- `src/main/java/com/tongji/profile/api/ProfileController.java`
- `src/main/java/com/tongji/auth/token/JwtService.java`
- `src/main/java/com/tongji/auth/token/RefreshTokenStore.java`
- `src/main/java/com/tongji/auth/verification/VerificationService.java`
- `src/main/java/com/tongji/auth/config/SecurityConfig.java`
- `src/main/java/com/tongji/user/service/impl/UserServiceImpl.java`
- `src/main/java/com/tongji/profile/service/impl/ProfileServiceImpl.java`

## 当前边界

- `auth` 负责身份和认证生命周期
- `profile` 负责用户资料展示与资料补丁
- `user` 负责用户主记录，不把 controller DTO 直接当成主记录定义
