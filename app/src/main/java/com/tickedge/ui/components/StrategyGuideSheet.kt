package com.tickedge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tickedge.domain.StrategyDefinition
import com.tickedge.ui.theme.BorderDark
import com.tickedge.ui.theme.StatRed
import com.tickedge.ui.theme.SurfaceDarkCard
import com.tickedge.ui.theme.TextPrimary
import com.tickedge.ui.theme.TextSecondary
import com.tickedge.ui.theme.VividYellow

/**
 * The info button that opens a strategy's guide.
 *
 * An "i" in a circle, not an eye: the guide is *information about* the strategy, and an eye
 * read as "something visual happens" rather than "explanation". The circle-and-letter is the
 * convention the user learns from every other app's help affordance.
 */
@Composable
fun StrategyGuideButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(Color(0xFF232734), RoundedCornerShape(8.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .testTag("strategy_guide_button"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "What does this strategy do?",
            tint = VividYellow,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * The strategy's guide, in words a beginner can follow.
 *
 * Every term the app shows elsewhere is explained here. The order is deliberate: what it does
 * first, then how a decision is made, then the words, then the risk — because the risk is the
 * thing the reader must not skip, and putting it last means it is read last, not never.
 */
@Composable
fun StrategyGuideSheet(
    strategy: StrategyDefinition,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val guide = strategy.guide

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceDarkCard, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .border(1.dp, BorderDark, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "How this strategy works",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = strategy.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF232734), RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .testTag("strategy_guide_close"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = guide.summary,
            fontSize = 13.sp,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "What it does, step by step",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = VividYellow
        )
        Spacer(modifier = Modifier.height(6.dp))
        guide.howItWorks.forEachIndexed { index, step ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${index + 1}.",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary
                )
                Text(
                    text = step,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "What the words mean",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = VividYellow
        )
        Spacer(modifier = Modifier.height(6.dp))
        guide.words.forEach { word ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    text = word.term,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = word.meaning,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // The risk, stated plainly and set apart. This is the part a beginner must read, so it is
        // not buried under the explanations.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2A1418), RoundedCornerShape(12.dp))
                .border(1.dp, StatRed, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = "What you are risking",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = StatRed
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = guide.riskNote,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}
