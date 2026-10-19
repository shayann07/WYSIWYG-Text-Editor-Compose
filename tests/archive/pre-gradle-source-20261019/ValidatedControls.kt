package editor

import java.awt.*
import javax.swing.*

/** The same components remain mounted until valid admission or explicit Cancel. */
internal fun validatedDialog(owner:JFrame,title:String,name:String,fields:List<Pair<String,JComponent>>,buttons:List<Pair<String,()->String?>>) {
    val dialog=JDialog(owner,title,true).apply { this.name=name;defaultCloseOperation=WindowConstants.DISPOSE_ON_CLOSE }
    val error=JLabel(" ").apply { this.name="validation-error";foreground=Color(0xb3261e);accessibleContext.accessibleName="Validation error" }
    val form=JPanel(GridLayout(0,2,8,6)).apply { fields.forEach { (label,field)->add(JLabel(label));add(field) } }
    val actions=JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        buttons.forEach { (label,perform)->add(JButton(label).apply { this.name=label.lowercase().replace(' ','-');addActionListener {
            val message=perform()
            if(message==null)dialog.dispose() else { error.text=message;error.toolTipText=message;dialog.pack();fields.firstOrNull()?.second?.requestFocusInWindow() }
        } }) }
        add(JButton("Cancel").apply { this.name="cancel";addActionListener { dialog.dispose() } })
    }
    dialog.contentPane=JPanel(BorderLayout(8,8)).apply { border=javax.swing.border.EmptyBorder(12,12,8,12);add(form,BorderLayout.CENTER);add(JPanel(BorderLayout()).apply { add(error,BorderLayout.NORTH);add(actions,BorderLayout.SOUTH) },BorderLayout.SOUTH) }
    dialog.rootPane.registerKeyboardAction({dialog.dispose()},KeyStroke.getKeyStroke("ESCAPE"),JComponent.WHEN_IN_FOCUSED_WINDOW)
    dialog.pack();dialog.setLocationRelativeTo(owner);dialog.isVisible=true
}

/** Ask the renderer/native UI for its size, plus breathing room for closed values. */
internal fun fitCombo(combo:JComboBox<*>) {
    val d=combo.preferredSize
    val fm=combo.getFontMetrics(combo.font)
    val width=(0 until combo.itemCount).maxOfOrNull { fm.stringWidth(combo.getItemAt(it).toString()) } ?: 0
    combo.preferredSize=Dimension(maxOf(d.width,width+combo.insets.left+combo.insets.right+36),maxOf(d.height,26))
    combo.minimumSize=combo.preferredSize
}
