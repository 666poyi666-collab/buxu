package com.poyi.watchintervals.phone;

/** User-facing Cloud V3 setup copy, kept separate from retained V2 migration code. */
public final class PhoneCloudSetupSpec {
    public static final String TITLE = "云端同步";
    public static final String ENDPOINT_HINT = "https://…/sync/v3/exchange";
    public static final String TOKEN_HINT = "设备 token（由系统安全保存）";
    public static final String SAVE_ACTION = "保存并测试云同步";
    public static final String SECURITY_NOTE = "Cloud V3 设备凭据由 Android Keystore 保护";

    private PhoneCloudSetupSpec() {}
}
