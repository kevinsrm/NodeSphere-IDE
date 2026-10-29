package com.mobile.nodeide

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** Lista informativa de plugins e runtimes; não instala pacotes. */
class PluginsFragment : Fragment() {

    private val names = arrayOf("Node.js", "npm", "Terminal Android", "SQLite", "MariaDB", "MySQL")
    private val descriptions = arrayOf(
        "Runtime externo necessário; não incluído",
        "Requer Node.js e npm externos",
        "Shell do Android disponível no editor",
        "Motor SQLite nativo do Android; CLI não incluída",
        "Runtime não incluído",
        "Runtime não incluído"
    )

    private lateinit var rows: Rows
    private val matches = mutableListOf<Int>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_list, container, false)
        view.findViewById<TextView>(R.id.title).text = "Plugins & runtimes"

        val search = view.findViewById<EditText>(R.id.search)
        search.hint = "Pesquisar plugins"

        val list = view.findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(requireContext())
        rows = Rows { position ->
            Toast.makeText(context, descriptions[matches[position]], Toast.LENGTH_LONG).show()
        }
        list.adapter = rows

        view.findViewById<Button>(R.id.action).visibility = View.GONE

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refresh(s?.toString().orEmpty())
            }
            override fun afterTextChanged(e: Editable?) = Unit
        })

        refresh("")
        return view
    }

    private fun refresh(query: String) {
        val normalized = query.lowercase(Locale.ROOT)
        matches.clear()
        val labels = mutableListOf<String>()
        for (index in names.indices) {
            if (names[index].lowercase(Locale.ROOT).contains(normalized)) {
                matches.add(index)
                labels.add("✣   ${names[index]}\n      ${descriptions[index]}")
            }
        }
        rows.set(labels)
    }
}
