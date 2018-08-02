package cn.jiang.dao;

import cn.jiang.bean.Pay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * @author jiang
 */
public interface PayDao extends JpaRepository<Pay,String>, JpaSpecificationExecutor<Pay> {

    List<Pay> getByStateIs(Integer state);

    List<Pay> getByStateIsNotAndStateIsNot(Integer state1,Integer state2);
}
