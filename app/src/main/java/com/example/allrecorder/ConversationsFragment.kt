package com.example.allrecorder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentConversationsBinding

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!

    private lateinit var conversationAdapter: ConversationAdapter
    private lateinit var conversationDao: ConversationDao

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. Get a reference to the database DAO
        conversationDao = AppDatabase.getDatabase(requireContext()).conversationDao()

        // 2. Set up the RecyclerView and its Adapter
        setupRecyclerView()

        // 3. Observe the database for changes and update the adapter
        conversationDao.getAllConversations().observe(viewLifecycleOwner, Observer { conversations ->
            conversations?.let {
                conversationAdapter.submitList(it)
            }
         })
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationAdapter()
        binding.recyclerView.apply {
            adapter = conversationAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}