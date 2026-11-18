package org.koitharu.kotatsu

import com.itos.coui.material3.CouiMaterial3Style
import com.itos.coui.theme.Coui
import org.koitharu.kotatsu.core.BaseApp

class KotatsuApp : BaseApp() {

	override fun onCreate() {
		super.onCreate()
		Coui.initialize(defaultStyle = CouiMaterial3Style())
	}
}
