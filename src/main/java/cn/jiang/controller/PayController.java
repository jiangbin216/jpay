package cn.jiang.controller;

import cn.jiang.bean.Pay;
import cn.jiang.bean.dto.DataTablesResult;
import cn.jiang.bean.dto.PageVo;
import cn.jiang.bean.dto.Result;
import cn.jiang.common.utils.*;
import cn.jiang.service.PayService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * @author jiang
 */
@Controller
@Api(tags = "开放接口",description = "支付捐赠管理")
public class PayController {

    private static final Logger log= LoggerFactory.getLogger(PayController.class);

    @Autowired
    private PayService payService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private EmailUtils emailUtils;

    @Value("${ip.expire}")
    private Long IP_EXPIRE;

    @Value("${my.token}")
    private String MY_TOKEN;

    @Value("${email.sender}")
    private String EMAIL_SENDER;

    @Value("${email.receiver}")
    private String EMAIL_RECEIVER;

    @Value("${token.admin.expire}")
    private Long ADMIN_EXPIRE;

    @Value("${token.fake.expire}")
    private Long FAKE_EXPIRE;

    @Value("${fake.pre}")
    private String FAKE_PRE;

    @Value("${server.url}")
    private String SERVER_URL;

    @Value("${qrnum}")
    private Integer QRNUM;

    @RequestMapping(value = "/thanks/list",method = RequestMethod.GET)
    @ApiOperation(value = "获取捐赠列表")
    @ResponseBody
    public DataTablesResult getThanksList(int draw, int start, int length, @RequestParam("search[value]") String search,
                                          @RequestParam("order[0][column]") int orderCol, @RequestParam("order[0][dir]") String orderDir){

        //获取客户端需要排序的列
        String[] cols = {"nickName","payType", "money", "info", "state", "createTime"};
        String orderColumn = cols[orderCol];
        if(orderColumn == null) {
            orderColumn = "createTime";
        }
        //获取排序方式 默认为desc(asc)
        if(orderDir == null) {
            orderDir = "desc";
        }
        PageVo pageVo = new PageVo();
        int page = start/length + 1;
        pageVo.setPageNumber(page);
        pageVo.setPageSize(length);
        pageVo.setSort(orderColumn);
        pageVo.setOrder(orderDir);
        Pageable pageable = PageUtil.initPage(pageVo);

        DataTablesResult result=new DataTablesResult();
        Page<Pay> payPage;
        try {
            payPage = payService.getPayListByPage(1,search,pageable);
        }catch (Exception e){
            log.error(e.toString());
            result.setSuccess(false);
            result.setDraw(draw);
            result.setError("获取捐赠列表失败");
            return result;
        }
        for(Pay p : payPage.getContent()){
            p.setId("");
            p.setEmail(null);
            p.setTestEmail(null);
            p.setMobile(null);
            p.setCustom(null);
            p.setPayNum(null);
            p.setDevice(null);
        }
        result.setRecordsFiltered(Math.toIntExact(payPage.getTotalElements()));
        result.setRecordsTotal(Math.toIntExact(payPage.getTotalElements()));
        result.setData(payPage.getContent());
        result.setDraw(draw);
        result.setSuccess(true);
        return result;
    }

    @RequestMapping(value = "/pay/list",method = RequestMethod.GET)
    @ApiOperation(value = "获取未支付数据")
    @ResponseBody
    public DataTablesResult getPayList(){

        DataTablesResult result=new DataTablesResult();
        List<Pay> list=new ArrayList<>();
        try {
            list=payService.getNotPayList();
        }catch (Exception e){
            result.setSuccess(false);
            result.setError("获取未支付数据失败");
            return result;
        }
        result.setData(list);
        result.setSuccess(true);
        return result;
    }

    @RequestMapping(value = "/pay/check/list",method = RequestMethod.GET)
    @ApiOperation(value = "获取支付审核列表")
    @ResponseBody
    public DataTablesResult getCheckList(){

        DataTablesResult result=new DataTablesResult();
        List<Pay> list = new ArrayList<>();
        list = payService.getPayList(0);
        try {
            list=payService.getPayList(0);
        }catch (Exception e){
            result.setSuccess(false);
            result.setError("获取支付审核列表失败");
            return result;
        }
        result.setData(list);
        result.setSuccess(true);
        return result;
    }

    @RequestMapping(value = "/pay/{id}",method = RequestMethod.GET)
    @ApiOperation(value = "获取支付数据")
    @ResponseBody
    public Result<Object> getPayList(@PathVariable String id,
                                     @RequestParam(required = true) String token){

        String temp=redisTemplate.opsForValue().get(id);
        if(!token.equals(temp)){
            return new ResultUtil<Object>().setErrorMsg("无效的Token或链接");
        }
        Pay pay=null;
        try {
            pay=payService.getPay(getPayId(id));
        }catch (Exception e){
            return new ResultUtil<Object>().setErrorMsg("获取支付数据失败");
        }
        return new ResultUtil<Object>().setData(pay);
    }

    @RequestMapping(value = "/pay/add",method = RequestMethod.POST)
    @ApiOperation(value = "添加支付订单")
    @ResponseBody
    public Result<Object> addPay(@ModelAttribute Pay pay, HttpServletRequest request){

        if(StringUtils.isBlank(pay.getNickName())||StringUtils.isBlank(String.valueOf(pay.getMoney()))
                ||pay.getMoney().compareTo(new BigDecimal("1.00"))==-1
                ||StringUtils.isBlank(pay.getEmail())||!EmailUtils.checkEmail(pay.getEmail())){
            return new ResultUtil<Object>().setErrorMsg("请填写完整信息和正确的通知邮箱和金额");
        }
        if(pay.getCustom()==null){
            return new ResultUtil<Object>().setErrorMsg("缺少自定义金额参数");
        }
        //防炸库验证
        String ip= IpInfoUtils.getIpAddr(request);
        if("0:0:0:0:0:0:0:1".equals(ip)){
            ip="127.0.0.1";
        }
        String temp=redisTemplate.opsForValue().get(ip);
        Long expire = redisTemplate.getExpire(ip,TimeUnit.SECONDS);
        if(StringUtils.isNotBlank(temp)){
            return new ResultUtil<Object>().setErrorMsg("您提交的太频繁啦！请"+expire+"秒后再试");
        }

        try {
            if(pay.getCustom()!=null&&pay.getCustom()){
                //自定义金额生成四位数随机标识
                pay.setPayNum(StringUtils.getRandomNum());
            }else{
                // 从redis中取出num
                String key = "JPAY_NUM";
                String value=redisTemplate.opsForValue().get(key);
                // 初始化
                if(StringUtils.isBlank(value)){
                    redisTemplate.opsForValue().set(key,"0");
                }
                // 取出num
                String num  = String.valueOf(Integer.parseInt(redisTemplate.opsForValue().get(key))+1);
                if(QRNUM.equals(Integer.valueOf(num))){
                    redisTemplate.opsForValue().set(key, "0");
                }else{
                    // 更新记录num
                    redisTemplate.opsForValue().set(key, String.valueOf(num));
                }
                pay.setPayNum(num);
            }
            payService.addPay(pay);
            pay.setTime(StringUtils.getTimeStamp(new Date()));
        }catch (Exception e){
            log.error(e.toString());
            return new ResultUtil<Object>().setErrorMsg("添加捐赠支付订单失败");
        }
        //记录缓存
        redisTemplate.opsForValue().set(ip,"added",IP_EXPIRE, TimeUnit.MINUTES);

        //给管理员发送审核邮件
        String tokenAdmin= UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(pay.getId(),tokenAdmin,ADMIN_EXPIRE,TimeUnit.DAYS);
        pay=getAdminUrl(pay,pay.getId(),tokenAdmin,MY_TOKEN);
        emailUtils.sendTemplateMail(EMAIL_SENDER,EMAIL_RECEIVER,"【JPay个人收款支付系统】待审核处理","email-admin",pay);

        //给假管理员发送审核邮件
        if(StringUtils.isNotBlank(pay.getTestEmail())&&EmailUtils.checkEmail(pay.getTestEmail())){
            Pay pay2=payService.getPay(pay.getId());
            String tokenFake=UUID.randomUUID().toString();
            redisTemplate.opsForValue().set(FAKE_PRE+pay.getId(),tokenFake,FAKE_EXPIRE,TimeUnit.HOURS);
            pay2=getAdminUrl(pay2,FAKE_PRE+pay.getId(),tokenFake,MY_TOKEN);
            emailUtils.sendTemplateMail(EMAIL_SENDER,pay.getTestEmail(),"【JPay个人收款支付系统】待审核处理","email-fake",pay2);
        }

        return new ResultUtil<Object>().setData(pay.getPayNum());
    }

    @RequestMapping(value = "/pay/edit",method = RequestMethod.POST)
    @ApiOperation(value = "编辑支付订单")
    @ResponseBody
    public Result<Object> editPay(@ModelAttribute Pay pay,
                                  @RequestParam(required = true) String id,
                                  @RequestParam(required = true) String token){

        String temp=redisTemplate.opsForValue().get(id);
        if(!token.equals(temp)){
            return new ResultUtil<Object>().setErrorMsg("无效的Token或链接");
        }
        try {
            pay.setId(getPayId(pay.getId()));
            Pay p=payService.getPay(getPayId(pay.getId()));
            pay.setState(p.getState());
            if(!pay.getId().contains(FAKE_PRE)){
                pay.setCreateTime(StringUtils.getDate(pay.getTime()));
            }else{
                //假管理
                pay.setMoney(p.getMoney());
                pay.setPayType(p.getPayType());
            }
            payService.updatePay(pay);
        }catch (Exception e){
            return new ResultUtil<Object>().setErrorMsg("编辑支付订单失败");
        }
        if(id.contains(FAKE_PRE)){
            redisTemplate.opsForValue().set(id,"",1L,TimeUnit.SECONDS);
        }
        return new ResultUtil<Object>().setData(null);
    }

    @RequestMapping(value = "/pay/pass",method = RequestMethod.GET)
    @ApiOperation(value = "审核通过支付订单")
    public String addPay(@RequestParam(required = true) String id,
                         @RequestParam(required = true) String token,
                         @RequestParam(required = true) String myToken,
                         @RequestParam(required = true) String sendType,
                         Model model){

        String temp=redisTemplate.opsForValue().get(id);
        if(!token.equals(temp)){
            model.addAttribute("errorMsg","无效的Token或链接");
            return "/500";
        }
        if(!myToken.equals(MY_TOKEN)){
            model.addAttribute("errorMsg","您未通过二次验证，当我傻吗");
            return "/500";
        }
        try {
            payService.changePayState(getPayId(id),1);
            //通知回调
            Pay pay=payService.getPay(getPayId(id));
            if(StringUtils.isNotBlank(pay.getEmail())&&EmailUtils.checkEmail(pay.getEmail())){
                if("0".equals(sendType)){
                    emailUtils.sendTemplateMail(EMAIL_SENDER,pay.getEmail(),"【JPay个人收款支付系统】支付成功通知","pay-success",pay);
                }else if("1".equals(sendType)){
                    emailUtils.sendTemplateMail(EMAIL_SENDER,pay.getEmail(),"【JPay个人收款支付系统】支付成功通知","sendwxcode",pay);
                }
            }
        }catch (Exception e){
            model.addAttribute("errorMsg","处理数据出错");
            return "/500";
        }
        return "redirect:/success";
    }

    @RequestMapping(value = "/pay/passNotShow",method = RequestMethod.GET)
    @ApiOperation(value = "审核通过但不显示加入捐赠表")
    public String passNotShowPay(@RequestParam(required = true) String id,
                                 @RequestParam(required = true) String token,
                                 Model model){

        String temp=redisTemplate.opsForValue().get(id);
        if(!token.equals(temp)){
            model.addAttribute("errorMsg","无效的Token或链接");
            return "/500";
        }
        try {
            payService.changePayState(getPayId(id),3);
            //通知回调
            Pay pay=payService.getPay(getPayId(id));
            if(StringUtils.isNotBlank(pay.getEmail())&&EmailUtils.checkEmail(pay.getEmail())){
                emailUtils.sendTemplateMail(EMAIL_SENDER,pay.getEmail(),"【JPay个人收款支付系统】支付成功通知","pay-notshow",pay);
            }
        }catch (Exception e){
            model.addAttribute("errorMsg","处理数据出错");
            return "/500";
        }
        if(id.contains(FAKE_PRE)){
            redisTemplate.opsForValue().set(id,"",1L,TimeUnit.SECONDS);
        }
        return "redirect:/success";
    }


    @RequestMapping(value = "/pay/back",method = RequestMethod.GET)
    @ApiOperation(value = "审核驳回支付订单")
    public String backPay(@RequestParam(required = true) String id,
                          @RequestParam(required = true) String token,
                          @RequestParam(required = true) String myToken,
                          Model model){

        String temp=redisTemplate.opsForValue().get(id);
        if(!token.equals(temp)){
            model.addAttribute("errorMsg","无效的Token或链接");
            return "/500";
        }
        if(!myToken.equals(MY_TOKEN)){
            model.addAttribute("errorMsg","您未通过二次验证，当我傻吗");
            return "/500";
        }
        try {
            payService.changePayState(getPayId(id),2);
            //通知回调
            Pay pay=payService.getPay(getPayId(id));
            if(StringUtils.isNotBlank(pay.getEmail())&&EmailUtils.checkEmail(pay.getEmail())){
                emailUtils.sendTemplateMail(EMAIL_SENDER,pay.getEmail(),"【JPay个人收款支付系统】支付失败通知","pay-fail",pay);
            }
        }catch (Exception e){
            model.addAttribute("errorMsg","处理数据出错");
            return "/500";
        }
        if(id.contains(FAKE_PRE)){
            redisTemplate.opsForValue().set(id,"",1L,TimeUnit.SECONDS);
        }
        return "redirect:/success";
    }

    @RequestMapping(value = "/pay/del",method = RequestMethod.GET)
    @ApiOperation(value = "删除支付订单")
    @ResponseBody
    public Result<Object> delPay(@RequestParam(required = true) String id,
                         @RequestParam(required = true) String token){

        String temp=redisTemplate.opsForValue().get(id);
        if(!token.equals(temp)){
            return new ResultUtil<Object>().setErrorMsg("无效的Token或链接");
        }
        try {
            //通知回调
            Pay pay=payService.getPay(getPayId(id));
            if(StringUtils.isNotBlank(pay.getEmail())&&EmailUtils.checkEmail(pay.getEmail())){
                emailUtils.sendTemplateMail(EMAIL_SENDER,pay.getEmail(),"【JPay个人收款支付系统】支付失败通知","pay-fail",pay);
            }
            payService.delPay(getPayId(id));
        }catch (Exception e){
            log.error(e.getMessage());
            return new ResultUtil<Object>().setErrorMsg("删除支付订单失败");
        }
        if(id.contains(FAKE_PRE)){
            redisTemplate.opsForValue().set(id,"",1L,TimeUnit.SECONDS);
        }
        return new ResultUtil<Object>().setData(null);
    }

    /**
     * 拼接管理员链接
     */
    public Pay getAdminUrl(Pay pay,String id,String token,String myToken){

        String pass=SERVER_URL+"/pay/pass?sendType=0&id="+id+"&token="+token+"&myToken="+myToken;
        pay.setPassUrl(pass);

        String pass2=SERVER_URL+"/pay/pass?sendType=1&id="+id+"&token="+token+"&myToken="+myToken;
        pay.setPassUrl2(pass2);

        String back=SERVER_URL+"/pay/back?id="+id+"&token="+token+"&myToken="+myToken;
        pay.setBackUrl(back);

        String passNotShow=SERVER_URL+"/pay/passNotShow?id="+id+"&token="+token;
        pay.setPassNotShowUrl(passNotShow);

        String edit=SERVER_URL+"/pay-edit?id="+id+"&token="+token;
        pay.setEditUrl(edit);

        String del=SERVER_URL+"/pay-del?id="+id+"&token="+token;
        pay.setDelUrl(del);
        return pay;
    }

    /**
     * 获得假管理ID
     * @param id
     * @return
     */
    public String getPayId(String id){
        if(id.contains(FAKE_PRE)){
            String realId=id.substring(id.indexOf("-",0)+1,id.length());
            return realId;
        }
        return id;
    }
}
