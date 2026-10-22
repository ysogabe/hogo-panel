package io.github.ysogabe.hogo.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelLayoutTest {
    private fun inStage(b: Box) {
        assertTrue("$b", b.left >= 0f && b.top >= 0f && b.right <= 960f + 0.01f && b.bottom <= 480f + 0.01f)
    }

    @Test
    fun everydayLayoutIsThreeLargeColumnsInsideTheFrame() {
        val boxes = PanelLayout.careBoxes(3, withReminder = false)
        assertEquals(3, boxes.size)
        boxes.forEach(::inStage)
        boxes.zipWithNext().forEach { (a, b) -> assertFalse(a.intersects(b)) }
        boxes.forEach { assertEquals(boxes[0].width, it.width, 0.01f) }
        assertTrue(boxes[0].width > 280f)
        assertFalse(boxes[0].intersects(PanelLayout.header))
    }

    @Test
    fun reminderLayoutPlacesMedsLeftAndSmallCareRightWithoutOverlap() {
        for (count in 1..2) {
            val meds = PanelLayout.medBoxes(count)
            val cares = PanelLayout.careBoxes(3, withReminder = true)
            (meds + cares).forEach(::inStage)
            for (m in meds) for (c in cares) assertFalse(m.intersects(c))
            meds.zipWithNext().forEach { (a, b) -> assertFalse(a.intersects(b)) }
            cares.zipWithNext().forEach { (a, b) -> assertFalse(a.intersects(b)) }
            assertTrue(meds.all { it.right < cares[0].left })
        }
        assertEquals(PanelLayout.content.height, PanelLayout.medBoxes(1)[0].height, 0.01f)
        assertEquals(PanelLayout.content.height, PanelLayout.medBoxes(2).let { it[1].bottom - it[0].top }, 0.01f)
    }

    @Test
    fun touchTargetsAreLargeEnoughOnA195DpiScreen() {
        // 195dpi で 48dp 相当 ≒ 58px。基準座標は物理 px。
        val minPx = 58f
        val all = PanelLayout.careBoxes(3, true) + PanelLayout.medBoxes(2) + PanelLayout.clockButton +
            PanelLayout.dialog(true).let { listOf(it.back, it.done) } + PanelLayout.toastUndoHit +
            PanelLayout.menu.items
        all.forEach { assertTrue("$it", it.width >= minPx && it.height >= minPx) }
    }

    @Test
    fun dialogToastAndMenuFitInsideTheFrame() {
        for (symbol in listOf(true, false)) {
            val d = PanelLayout.dialog(symbol)
            listOf(d.panel, d.back, d.done).forEach(::inStage)
            assertFalse(d.back.intersects(d.done))
            assertTrue(d.panel.contains(d.back.left, d.back.top) && d.panel.contains(d.done.right - 1, d.done.bottom - 1))
        }
        inStage(PanelLayout.toast)
        inStage(PanelLayout.toastUndoVisual)
        assertTrue(PanelLayout.toast.contains(PanelLayout.toastUndoHit.left, PanelLayout.toastUndoHit.top))
        val m = PanelLayout.menu
        inStage(m.panel)
        m.items.forEach { inStage(it); assertTrue(m.panel.contains(it.centerX, it.centerY)) }
        m.items.forEachIndexed { i, a -> m.items.drop(i + 1).forEach { assertFalse(a.intersects(it)) } }
        assertEquals(8, m.items.size)
    }

    @Test
    fun headerHoldAreaExcludesTheClockButton() {
        assertFalse(PanelLayout.headerHold.intersects(PanelLayout.clockButton))
        assertTrue(PanelLayout.header.contains(PanelLayout.clockButton.centerX, PanelLayout.clockButton.centerY))
    }

    @Test
    fun stageScalesToTheDeviceWithoutUsingDp() {
        val device = Stage.fit(960f, 480f)
        assertEquals(1f, device.scale, 0f)
        assertEquals(0f, device.offsetX, 0f)
        assertEquals(0f, device.offsetY, 0f)
        val big = Stage.fit(1280f, 800f)
        assertEquals(1280f / 960f, big.scale, 0.0001f)
        assertEquals(0f, big.offsetX, 0.01f)
        assertEquals((800f - 480f * big.scale) / 2f, big.offsetY, 0.01f)
        assertEquals(240f, big.toBaseY(400f), 0.01f) // 画面中央が基準の中央
        val small = Stage.fit(480f, 240f)
        assertEquals(0.5f, small.scale, 0f)
        assertEquals(960f, small.toBaseX(480f), 0.01f)
    }

    // ---- 当たり判定 ----

    private fun model(s: Scenario, now: Long = 0L) = PanelModel(now).also { it.perform(MenuAction.Pick(s), now) }

    @Test
    fun everydayHitsAreCareOnlyAndHeaderRegionsAreSeparate() {
        val m = model(Scenario.NORMAL)
        val boxes = PanelLayout.careBoxes(3, false)
        boxes.forEachIndexed { i, b -> assertEquals(Hit.Care(i), PanelHits.at(m, b.centerX, b.centerY, 0)) }
        assertEquals(Hit.ClockButton, PanelHits.at(m, PanelLayout.clockButton.centerX, PanelLayout.clockButton.centerY, 0))
        assertEquals(Hit.HeaderHold, PanelHits.at(m, 100f, 38f, 0))
        assertNull(PanelHits.at(m, 2f, 470f, 0))
    }

    @Test
    fun reminderHitsSelectTheCardThatIsActuallyShown() {
        val m = model(Scenario.SOON)
        val meds = PanelLayout.medBoxes(2)
        assertEquals(Hit.Med("filaria-20261020"), PanelHits.at(m, meds[0].centerX, meds[0].centerY, 0))
        assertEquals(Hit.Med("frontline-20261020"), PanelHits.at(m, meds[1].centerX, meds[1].centerY, 0))
        m.openMed("filaria-20261020", 0)
        m.confirm(500)
        // 残り 1 枚は大きなカード 1 枚で、元の 2 枚目の位置もその薬になる。
        val one = PanelLayout.medBoxes(1)[0]
        assertEquals(Hit.Med("frontline-20261020"), PanelHits.at(m, one.centerX, one.centerY, 600))
    }

    @Test
    fun disabledDoneButtonSwallowsTapsAndEnablesAt450ms() {
        val m = model(Scenario.SOON)
        m.openMed("filaria-20261020", 100)
        val d = PanelLayout.dialog(true)
        assertEquals(Hit.Swallow, PanelHits.at(m, d.done.centerX, d.done.centerY, 100))
        assertEquals(Hit.Swallow, PanelHits.at(m, d.done.centerX, d.done.centerY, 549))
        assertEquals(Hit.DialogDone, PanelHits.at(m, d.done.centerX, d.done.centerY, 550))
        assertEquals(Hit.DialogBack, PanelHits.at(m, d.back.centerX, d.back.centerY, 100))
        assertEquals(Hit.Scrim, PanelHits.at(m, 5f, 5f, 100))
        assertEquals(Hit.Swallow, PanelHits.at(m, d.panel.centerX, d.panel.top + 8f, 100))
    }

    @Test
    fun clockScreenHasNoHits() {
        val m = model(Scenario.SOON)
        m.enterClock()
        val b = PanelLayout.medBoxes(2)[0]
        assertNull(PanelHits.at(m, b.centerX, b.centerY, 0))
    }

    @Test
    fun medCardsWithMoreLeaveABandBelowTheSecondCard() {
        val meds = PanelLayout.medBoxes(2, withMore = true)
        val more = PanelLayout.moreBox
        meds.forEach(::inStage)
        inStage(more)
        assertFalse(meds[0].intersects(meds[1]))
        meds.forEach { assertFalse(it.intersects(more)) }
        assertTrue(meds[1].bottom < more.top)
        assertEquals(PanelLayout.content.bottom, more.bottom, 0.01f)
        assertEquals(PanelLayout.content.top, meds[0].top, 0.01f)
        // カードは「ほか」が無いときより低いが、タッチ対象として十分な高さを保つ。
        assertTrue(meds[0].height < PanelLayout.medBoxes(2)[0].height)
        assertTrue(meds[0].height >= 58f)
        PanelLayout.careBoxes(3, withReminder = true).forEach { c ->
            meds.forEach { assertFalse(it.intersects(c)) }
            assertFalse(more.intersects(c))
        }
    }

    @Test
    fun withoutMoreTheCardGeometryIsUnchanged() {
        assertEquals(PanelLayout.medBoxes(2), PanelLayout.medBoxes(2, withMore = false))
        assertEquals(PanelLayout.content.height, PanelLayout.medBoxes(1)[0].height, 0.01f)
    }

    @Test
    fun threeWarningsHitTestOnlyTheTwoShownCards() {
        val m = PanelModel(0L).also { it.enterApiMode(java.time.LocalDate.of(2026, 10, 22)) }
        val today = java.time.LocalDate.of(2026, 10, 22)
        m.applySchedule(
            ScheduleResult.Loaded(
                SourceStatus.OK, 2, null, null, null,
                listOf(
                    Occurrence("f", "f", "F", MedIcon.PILL, today.plusDays(2)),
                    Occurrence("l", "l", "L", MedIcon.DROP, today),
                    Occurrence("m", "m", "M", MedIcon.PILL, today.minusDays(1)),
                ),
            ),
            today,
        )
        val boxes = PanelLayout.medBoxes(2, withMore = true)
        assertEquals(Hit.Med("m"), PanelHits.at(m, boxes[0].centerX, boxes[0].centerY, 0L))
        assertEquals(Hit.Med("l"), PanelHits.at(m, boxes[1].centerX, boxes[1].centerY, 0L))
        // 「ほか N 件」の帯は何も起こさない。
        assertEquals(null, PanelHits.at(m, PanelLayout.moreBox.centerX, PanelLayout.moreBox.centerY, 0L))
    }
}
