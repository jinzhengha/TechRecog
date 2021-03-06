package com.safe;

import android.app.Application;
import android.app.Service;
import android.hardware.Camera;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.Helper.RecogResult;
import com.LPR;
import com.util.EncryptionUtil;
import com.util.SpUtil;
import com.util.TagUtil;
import com.utils.CityCodeUtil;
import com.utils.Consts;
import com.utils.RecogFileUtil;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import static com.Helper.ComRecogHelper.isPic;

public class RecogHelperSafe {
    private static final int DEFAULT_SCOPE = 85; //合格分数
    protected static RecogHelperSafe recogHelper;
    public static Application mContext;


    public Random random;


    public RecogHelperSafe(String cityName) {
        init(cityName);
    }

    //isInitConfig 是否初始化参数   相机页面一定要设为true否则无法识别
    //cityName  默认的第一个汉字 如：粤
    public static synchronized RecogHelperSafe getDefault(Application context, boolean isInitConfig, String cityName) {
        synchronized (RecogHelperSafe.class) {
            if (isInitConfig)//初始化参数
                initConfig();
            mContext = (mContext == null ? context : mContext);
            return recogHelper == null ? recogHelper = new RecogHelperSafe(cityName) : recogHelper;
        }
    }


    //初始化参数
    public static void initConfig() {
        TagUtil.showLogDebug("initConfig");
        Consts.recogingDegger = 0;
        time = System.currentTimeMillis();
        bestScope = 0;
        tempCarnum = "";
    }

    //初始化
    public boolean init(String cityName) {
        spUtil=new SpUtil(mContext,Consts.SP_PERMITION);
        random = new Random();
        //初始化识别算法
        String sdDir = RecogFileUtil.getSdPatch(mContext);
        if (sdDir == null) {
            Toast.makeText(mContext, "找不到存储路径", Toast.LENGTH_LONG).show();
            return false;
        }
        String ImgPath = sdDir + "/mTest/Img/";
        Consts.IMAGGE_DIR = ImgPath;
        String modelPath = sdDir + "/mTest/data/";

        File f1 = new File(ImgPath);
        File f2 = new File(modelPath);
        if (!f1.exists()) {
            f1.mkdirs();
        }
        if (!f2.exists()) {
            f2.mkdirs();
        }

        try {
            Log.d("tagutil", String.format("current time is in java : %d", System.currentTimeMillis()));
            LPR.init(modelPath.getBytes("GBK"), CityCodeUtil.getCityCode(cityName, mContext), mContext);
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
            return false;

        }
        return true;
    }


    SpUtil spUtil;

    //是否获取过权限
    public boolean isCheckPermition() {
        if (!Consts.IS_CHECK_PERMITION) {
            savePermitionInfo(true);
        } else {
            savePermitionInfo(false);
        }
        return (boolean) spUtil.getData(Consts.IS_GETEDPERMITION, Boolean.class, false);
    }

    //保存权限状态     flag：true  已获取
    public void savePermitionInfo(boolean flag) {
        if (spUtil == null) {
            spUtil = new SpUtil(mContext, Consts.SP_PERMITION);
        }
        spUtil.save(Consts.IS_GETEDPERMITION, flag);
    }


    public static String tempCarnum = "";
    public static long time;  //开始识别时间
    public final static int MAX_DEGGER = 3;//最高前后比对次数
    public static final int MATCHING_LENG = 4; //匹配车牌长度


    public static int bestScope;//上一次的识别率

    public synchronized void getCarnum(final byte[] data, final Camera camera, final RecogResult recogToken) {
        getCarnum(data, camera.getParameters().getPreviewSize().width, camera.getParameters().getPreviewSize().height, recogToken);
    }

    public synchronized void getCarnum(final byte[] data, int width, int height, final RecogResult recogToken) {
        if (!permitionCheck(recogToken)) {
            return;
        }
        synchronized (recogHelper.getClass()) {
            if (data != null) {
                Consts.orgdata = data;
                //加密
                int location = random.nextInt(EncryptionUtil.getLocation(EncryptionUtil.MYRECOG_SCOPE));
                if (data.length > location) {
                    EncryptionUtil.setPoint(data, location);
                } else {
                    recogToken.recogFail();
                    return;
                }

                String platenum = "";
                int scope = 0;
                try {
                    LPR.locate(width, height, data); //0-255
                    platenum = new String(LPR.getplate(0), "GBK").trim();
                    scope = LPR.getplatescore(0);
                } catch (Exception e) {
                    e.printStackTrace();
                }


//                Log.d("number1", TextUtils.isEmpty(platenum) ? "返回空  " : platenum + "正确率="+scope);

                //第一次不往下走
                if (!isPic
                        && TextUtils.isEmpty(tempCarnum.trim())
                        && isNumber(platenum)) {
                    tempCarnum = platenum.trim();
                    if (scope < 80) {
                        recogToken.recogFail();
                        Log.d("number2", "第一次获取 tempnum=" + tempCarnum);
                        return;
                    }

                }

                boolean getedSuccess;//车牌是否获取成功
                if (isPic) {
                    getedSuccess = isNumber(platenum);
                } else {
                    getedSuccess = isNumber(platenum) && (isScopeOk(scope) || tempCarnum.equals(platenum));
                    Log.d("number2", getedSuccess ? platenum : "返回空" + "\nplatenum=" + platenum + "\ntempnum=" + tempCarnum);
                }

                if (getedSuccess) {
                    Consts.orgw = width;
                    Consts.orgh = height;
                    Consts.speed = (System.currentTimeMillis() - time) / 1000.0f;
                    time = System.currentTimeMillis();
                    Consts.platenum = platenum;
                    recogToken.recogSuccess(platenum, data);
                    tempCarnum = "";
                } else {
                    Consts.orgdata = null;
                    Consts.orgw = 0;
                    Consts.orgh = 0;
                    recogToken.recogFail();
                    tempCarnum = isNumber(platenum) ? platenum : tempCarnum;//初始化中间车牌
                }
            }
        }
    }


    /****************************************
     方法描述：使用时间限制来获取车牌
     @param  maxTime 识别时长 单位秒
     @return
     ****************************************/

    public synchronized void getCarnumByTime(final byte[] data, final Camera camera, final RecogResult recogToken, int maxTime) {
        if (!permitionCheck(recogToken)) {
            return;
        }
        synchronized (recogHelper.getClass()) {

            if (data != null) {
                Consts.orgdata = data;
                //加密
                int width = camera.getParameters().getPreviewSize().width;
                int height = camera.getParameters().getPreviewSize().height;
                int location = random.nextInt(EncryptionUtil.getLocation(EncryptionUtil.MYRECOG_SCOPE));
                if (data.length > location) {
                    EncryptionUtil.setPoint(data, location);
                } else {
                    recogToken.recogFail();
                    return;
                }

                String platenum = "";
                try {
                    LPR.locate(width, height, data); //0-255
                    platenum = new String(LPR.getplate(0), "GBK").trim();

                } catch (Exception e) {
                    e.printStackTrace();
                }


//                Log.d("number1", TextUtils.isEmpty(platenum) ? "返回空  " : platenum + "正确率="+scope);

                //第一次不往下走
                if (!isPic
                        && TextUtils.isEmpty(tempCarnum.trim())
                        && isNumber(platenum)) {
                    tempCarnum = platenum.trim();
                    recogToken.recogFail();
                    Log.d("number2", "第一次获取 tempnum=" + tempCarnum);
                    return;
                }

                boolean getedSuccess;//车牌是否获取成功
                if (isPic) {
                    getedSuccess = isNumber(platenum);
                } else {
                    getedSuccess = ((isNumber(platenum) && tempCarnum.equals(platenum)))
                            || (!TextUtils.isEmpty(platenum) && (System.currentTimeMillis() - time) / 1000 >= maxTime);   //超过时间限制
                    Log.d("number2", getedSuccess ? platenum : "返回空" + "\nplatenum=" + platenum + "\ntempnum=" + tempCarnum);
                }

                if (getedSuccess) {
                    Consts.orgw = width;
                    Consts.orgh = height;
                    Consts.speed = (System.currentTimeMillis() - time) / 1000.0f;
                    time = System.currentTimeMillis();
                    Consts.platenum = platenum;
                    recogToken.recogSuccess(platenum, data);
                    tempCarnum = "";

                } else {
                    Consts.orgdata = null;
                    Consts.orgw = 0;
                    Consts.orgh = 0;
                    recogToken.recogFail();
                    tempCarnum = isNumber(platenum) ? platenum : tempCarnum;//初始化中间车牌
                    Log.d("number3", "匹配" + (!TextUtils.isEmpty(platenum) ? platenum.substring(0, MATCHING_LENG) : "") + "  " + tempCarnum);
                }
            }
        }
    }

    //判断是否是车牌
    public boolean isNumber(String platenum) {
        return !TextUtils.isEmpty(platenum) && platenum.trim().length() > 3;
    }

    //成绩是否达标
    public boolean isScopeOk(int scope) {
        return scope > DEFAULT_SCOPE;
    }

    public Camera.Parameters offFlash(Camera.Parameters parameters) {

        if (parameters != null) {
            if (parameters == null) return parameters;
            String flashMode = parameters.getFlashMode();
            if (flashMode.equals(Camera.Parameters.FLASH_MODE_TORCH)) {
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                parameters.setExposureCompensation(0);
            }
        }
        return parameters;
    }

    public Camera.Parameters openFlash(Camera.Parameters parameters) {
        if (parameters != null) {
            String flashMode = parameters.getFlashMode();
            if (!flashMode.equals(Camera.Parameters.FLASH_MODE_TORCH)) {
                parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
                parameters.setExposureCompensation(0);
            }
        }
        return parameters;
    }

    /**
     * 方法描述：检查权限
     * <p/>
     *
     * @param
     * @return
     */
    public boolean permitionCheck(RecogResult recogToken) {
        if (Consts.IS_CHECK_PERMITION) {
            //从sp文件确认
            //文件比对
            if ((boolean) spUtil.getData(Consts.IS_GETEDPERMITION, Boolean.class, false)) {  //获取过权限则不验证
                recogToken.permitionSuccess();
                Consts.IS_CHECK_PERMITION = false;
                return true;
            }
            String sdDir = RecogFileUtil.getSdPatch(mContext);
            TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Service.TELEPHONY_SERVICE);
            try {
                String imeiSha = EncryptionUtil.getSHA(tm.getDeviceId());
                String fileSha = RecogFileUtil.getString(sdDir + Consts.SPATH);
                if (TextUtils.isEmpty(imeiSha) || TextUtils.isEmpty(fileSha) || !imeiSha.equals(fileSha)) {
                    recogToken.permitionFail();
                    savePermitionInfo(false);
                } else {
                    recogToken.permitionSuccess();
                    savePermitionInfo(true);
                }
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                recogToken.permitionFail();
                savePermitionInfo(false);
            }

        } else {
            recogToken.permitionSuccess();
            return true;
        }
        return false;
    }


}
