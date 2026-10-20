package com.example.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课程分类弹层的落库规则。
 *
 * 选完一个类别之后，弹层**留在这一门**（换下一门由用户自己横滑）——
 * 这一点是用户明确要的：之前是「选完自动跳下一门」，那一跳是 App 替用户
 * 做的决定，他还没看清刚改的那一门就被带走了。
 *
 * 这里钉的是**剩下**的那条规则：选的就是当前类别时**不写库**。
 * 一次「什么都没发生」不该配一句「已归到 X」，也不该动数据库。
 */
class CourseCategoryDialogTest {

    @Test
    fun `选了别的类别就写库`() {
        assertTrue(shouldPersistCategory(currentCategory = "公共基础", pickedCategory = "实验实践"))
    }

    @Test
    fun `选的就是当前类别就不写库`() {
        assertFalse(shouldPersistCategory(currentCategory = "公共基础", pickedCategory = "公共基础"))
    }
}
