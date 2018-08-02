package cn.jiang.common.task;

import cn.jiang.bean.Pay;
import cn.jiang.common.utils.EmailUtils;
import cn.jiang.common.utils.StringUtils;
import cn.jiang.dao.PayDao;
import cn.jiang.service.PayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * @author jiang
 */
@Component
public class Jobs {

    final static Logger log= LoggerFactory.getLogger(Jobs.class);

    @Autowired
    private PayService payService;

    @Autowired
    private PayDao payDao;

    @Autowired
    private EmailUtils emailUtils;

    @Value("${email.sender}")
    private String EMAIL_SENDER;

    /**
     * 每日凌晨清空除捐赠和审核中以外的数据
     */
    @Scheduled(cron="0 0 0 * * ?")
    public void cronJob(){

        List<Pay> list=payDao.getByStateIsNotAndStateIsNot(0,1);
        for(Pay p:list){
            try {
                payService.delPay(p.getId());
            }catch (Exception e){
                log.error("定时删除数据"+p.getId()+"失败");
                e.printStackTrace();
            }
        }

        log.info("定时执行清空除捐赠和审核中的数据完毕");

        //设置未审核数据为支付失败
        List<Pay> list1=payDao.getByStateIs(0);
        for(Pay p:list1){
            p.setState(2);
            p.setUpdateTime(new Date());
            try {
                payService.updatePay(p);
            }catch (Exception e){
                log.error("设置未审核数据"+p.getId()+"为支付失败");
                e.printStackTrace();
            }
        }

        log.info("定时执行设置未审核数据为支付失败完毕");
    }

    /**
     * 每日10am统一发送支付失败邮件
     */
    @Scheduled(cron="0 0 10 * * ?")
    public void sendEmailJob(){

        List<Pay> list1=payDao.getByStateIs(2);
        list1.forEach(item -> item.setTime(StringUtils.getTimeStamp(item.getCreateTime())));
        for(Pay p:list1){
            if(StringUtils.isNotBlank(p.getEmail())&&EmailUtils.checkEmail(p.getEmail())) {
                emailUtils.sendTemplateMail(EMAIL_SENDER, p.getEmail(), "【JPay个人收款支付系统】支付失败通知", "pay-fail", p);
            }
        }

        log.info("定时执行统一发送支付失败邮件完毕");
    }
}
