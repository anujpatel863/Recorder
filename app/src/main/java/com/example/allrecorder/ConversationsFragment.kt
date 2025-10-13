package com.example.allrecorder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.allrecorder.databinding.FragmentConversationsBinding
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            conversationDao.getAllConversations().collect { conversations ->
                if (conversations.isNullOrEmpty()) {
                    binding.recyclerView.isVisible = false
                    binding.tvNoConversations.isVisible = true
                } else {
                    binding.recyclerView.isVisible = true
                    binding.tvNoConversations.isVisible = false
                    conversationAdapter.submitList(conversations)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationAdapter { conversation ->
            // Handle item click here. For example, navigate to ConversationDetailActivity
            val intent = Intent(requireContext(), ConversationDetailActivity::class.java)
            intent.putExtra("conversationId", conversation.id)
            startActivity(intent)
        }
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